package datawave.query.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import datawave.query.config.ShardQueryConfiguration;
import datawave.query.planner.QueryPlan;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.TableDeletedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.TableOfflineException;
import org.apache.accumulo.core.client.impl.ClientContext;
import org.apache.accumulo.core.client.impl.Tables;
import org.apache.accumulo.core.client.impl.TabletLocator;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.master.state.tables.TableState;
import org.apache.accumulo.core.client.impl.Credentials;
import org.apache.commons.jexl2.parser.ParseException;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;

import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import datawave.query.jexl.visitors.JexlStringBuildingVisitor;
import datawave.query.tables.SessionOptions;
import datawave.query.tables.async.ScannerChunk;
import datawave.webservice.query.configuration.QueryData;

public class PushdownFunction implements Function<QueryData,List<ScannerChunk>> {
    
    /**
     * Logger
     */
    private static final Logger log = Logger.getLogger(PushdownFunction.class);
    
    /**
     * Configuration object
     */
    private ShardQueryConfiguration config;
    
    /**
     * Tablet locator
     */
    private TabletLocator tl;
    
    /**
     * Set of query plans
     */
    protected Set<Integer> queryPlanSet;
    protected Collection<IteratorSetting> customSettings;
    
    protected String tableId = "0";
    
    public PushdownFunction(TabletLocator tl, ShardQueryConfiguration config, Collection<IteratorSetting> settings, String tableId) {
        this.tl = tl;
        this.config = config;
        queryPlanSet = Sets.newHashSet();
        this.customSettings = settings;
        this.tableId = tableId;
        
    }
    
    public List<ScannerChunk> apply(QueryData qd) {
        Multimap<String,QueryPlan> serverPlan = ArrayListMultimap.create();
        List<ScannerChunk> chunks = Lists.newArrayList();
        try {
            
            redistributeQueries(serverPlan, tl, new QueryPlan(qd));
            
            for (String server : serverPlan.keySet()) {
                Collection<QueryPlan> plans = serverPlan.get(server);
                Set<QueryPlan> reducedSet = Sets.newHashSet(plans);
                for (QueryPlan plan : reducedSet) {
                    Integer hashCode = plan.hashCode();
                    if (queryPlanSet.contains(hashCode)) {
                        continue;
                    } else
                        queryPlanSet.clear();
                    
                    queryPlanSet.add(hashCode);
                    try {
                        
                        SessionOptions options = new SessionOptions();
                        
                        if (log.isTraceEnabled()) {
                            log.trace("setting ranges" + plan.getRanges());
                            log.trace("range set size" + plan.getSettings().size());
                        }
                        for (IteratorSetting setting : plan.getSettings()) {
                            options.addScanIterator(setting);
                        }
                        
                        for (IteratorSetting setting : customSettings) {
                            options.addScanIterator(setting);
                        }
                        
                        for (String cf : plan.getColumnFamilies()) {
                            options.fetchColumnFamily(new Text(cf));
                        }
                        
                        options.setQueryConfig(this.config);
                        
                        chunks.add(new ScannerChunk(options, Lists.newArrayList(plan.getRanges()), server));
                        
                    } catch (Exception e) {
                        log.error(e);
                        throw new AccumuloException(e);
                    }
                }
            }
            
        } catch (AccumuloException e) {
            throw new RuntimeException(e);
        } catch (AccumuloSecurityException e) {
            throw new RuntimeException(e);
        } catch (TableNotFoundException e) {
            throw new RuntimeException(e);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        return chunks;
    }
    
    protected void redistributeQueries(Multimap<String,QueryPlan> serverPlan, TabletLocator tl, QueryPlan currentPlan) throws AccumuloException,
                    AccumuloSecurityException, TableNotFoundException {
        
        List<Range> ranges = Lists.newArrayList(currentPlan.getRanges());
        if (ranges.size() > 0) {
            Map<String,Map<KeyExtent,List<Range>>> binnedRanges = binRanges(tl, config.getConnector().getInstance(), ranges);
            
            for (String server : binnedRanges.keySet()) {
                Map<KeyExtent,List<Range>> hostedExtentMap = binnedRanges.get(server);
                
                List<Range> rangeList = Lists.newArrayList();
                
                Iterable<Range> rangeIter = rangeList;
                
                for (Entry<KeyExtent,List<Range>> rangeEntry : hostedExtentMap.entrySet()) {
                    if (log.isTraceEnabled())
                        log.trace("Adding range from " + rangeEntry.getValue());
                    rangeIter = Iterables.concat(rangeIter, rangeEntry.getValue());
                }
                
                if (log.isTraceEnabled())
                    log.trace("Adding query tree " + JexlStringBuildingVisitor.buildQuery(currentPlan.getQueryTree()) + " " + currentPlan.getSettings().size()
                                    + " for " + server);
                
                serverPlan.put(server, new QueryPlan(currentPlan.getQueryTree(), rangeIter, currentPlan.getSettings(), currentPlan.getColumnFamilies()));
                
            }
        }
        
    }
    
    protected Map<String,Map<KeyExtent,List<Range>>> binRanges(TabletLocator tl, Instance instance, List<Range> ranges) throws AccumuloException,
                    AccumuloSecurityException, TableNotFoundException {
        Map<String,Map<KeyExtent,List<Range>>> binnedRanges = new HashMap<>();
        
        int lastFailureSize = Integer.MAX_VALUE;
        
        while (true) {
            
            binnedRanges.clear();
            AuthenticationToken authToken = new PasswordToken(config.getAccumuloPassword());
            Credentials creds = new Credentials(config.getConnector().whoami(), authToken);
            List<Range> failures = tl.binRanges(new ClientContext(instance, creds, AccumuloConfiguration.getDefaultConfiguration()), ranges, binnedRanges);
            
            if (failures.size() > 0) {
                // tried to only do table state checks when failures.size()
                // == ranges.size(), however this did
                // not work because nothing ever invalidated entries in the
                // tabletLocator cache... so even though
                // the table was deleted the tablet locator entries for the
                // deleted table were not cleared... so
                // need to always do the check when failures occur
                if (failures.size() >= lastFailureSize)
                    if (!Tables.exists(instance, tableId))
                        throw new TableDeletedException(tableId);
                    else if (Tables.getTableState(instance, tableId) == TableState.OFFLINE)
                        throw new TableOfflineException(instance, tableId);
                
                lastFailureSize = failures.size();
                
                if (log.isTraceEnabled())
                    log.trace("Failed to bin " + failures.size() + " ranges, tablet locations were null, retrying in 100ms");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                break;
            }
            
        }
        
        // truncate the ranges to within the tablets... this makes it easier
        // to know what work
        // needs to be redone when failures occurs and tablets have merged
        // or split
        Map<String,Map<KeyExtent,List<Range>>> binnedRanges2 = new HashMap<>();
        for (Entry<String,Map<KeyExtent,List<Range>>> entry : binnedRanges.entrySet()) {
            Map<KeyExtent,List<Range>> tabletMap = new HashMap<>();
            binnedRanges2.put(entry.getKey(), tabletMap);
            for (Entry<KeyExtent,List<Range>> tabletRanges : entry.getValue().entrySet()) {
                Range tabletRange = tabletRanges.getKey().toDataRange();
                List<Range> clippedRanges = new ArrayList<>();
                tabletMap.put(tabletRanges.getKey(), clippedRanges);
                for (Range range : tabletRanges.getValue())
                    clippedRanges.add(tabletRange.clip(range));
            }
        }
        
        binnedRanges.clear();
        binnedRanges.putAll(binnedRanges2);
        
        return binnedRanges;
    }
    
}
