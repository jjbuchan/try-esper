# try-esper
Testing Esper 8's ability to group/partition data by multiple fields

# Event Engine Design

## Diagram

[This](flow-diagram.puml) shows the typical flow of events for a new task and new metrics.

## TODO

- How are queries partitioned across event processors?
- Are all metrics ingested by all processors?
- What happens when a processor node goes down?
- What happens when a processor node is added?

## Event Engine Management

- Need to add `excludedResourceIds` to the Task object
- Sends `TaskEvent`s to Kafka for any create/update/delete

The design choice is to keep event-engine-management simple and push all new logic to event-engine-processor.  Specifically, event-engine-management should not need any knowledge of Esper.

## Event Engine Processor - Task Operations

### Task Creation

The esper query will look like

```sql
@name('{tenantId}:{taskId}')
insert into EntryWindow
select MetricEvaluation.generateEnrichedMetric(metric, {condition}, {stateCountMap}) from SalusEnrichedMetric(
    monitoringSystem='salus' and
    tenantId='my-tenant' and
    monitorScope='remote' and
    monitorType='http' and
    resourceId not in (excludedResourceIds) and
    tags('os')='linux' and tags('metric')='something') metric;
```
Note: the tags map cannot make use of an any/all/some/in operator - null issues occur

This query allows for a seen metric to be enriched with an evaluated state (based on the condition) and the stateCount values to help evaluate whether that criteria has been met or not.  This method allows us to easily expand on this in the future if more information is required by the query.

`condition` is what will be used to evaluate the state of the task
`stateCountMap` represent the critical, warning, ok stateCounts from the task and. This will allow any new metric seen to include the required stateCount criteria.  It will be a String->Integer map.

Here's an example of a `condition` used in ele:
```sql
case when(NOT(MetricHelper.isAvailable(ts)))
        then ObjectFactory.NewCondition(AlarmState.CRITICAL, ts.status)
     when(NOT(MetricHelper.isSet(metrics('banner_matched'))))
        then ObjectFactory.NewCondition(AlarmState.CRITICAL, 'metric "banner_matched" is not available')
     when(NOT(MetricHelper.isSet(metrics('banner'))))
        then ObjectFactory.NewCondition(AlarmState.CRITICAL, 'metric "banner" is not available')
     when(NOT(MetricHelper.isStringMetric(metrics('banner_matched')))) then ObjectFactory.NewCondition(AlarmState.CRITICAL, 'metric "banner_matched" is not string, but comparing it with a string value')
     when(NOT(MetricHelper.isStringMetric(metrics('banner'))))
        then ObjectFactory.NewCondition(AlarmState.CRITICAL, 'metric "banner" is not string, but comparing it with a string value')
     when(MetricHelper.getTValue(metrics('banner_matched')) != '')
        then ObjectFactory.NewCondition(AlarmState.OK, 'Matched return statement on line 3')
     when(MetricHelper.getTValue(metrics('banner')) regexp 'OpenSSH.*')
        then ObjectFactory.NewCondition(AlarmState.OK, 'Matched return statement on line 9')
     else ObjectFactory.NewCondition(AlarmState.CRITICAL, 'Match not found.') end)
```

### Task Updates
On any task update the existing query should be removed and then redeployed.

### Task Deletes
The task query can be `undeploy()`'ed from Esper using the tenantId and taskId.


## Event Engine Processor - Metric Processing

### Input

- Ingests `UniveralMetricFrame`s
- Parses out the individual metric values and enriches them into a `EnrichedMetricValue` object
- `EnrichedMetricValue` will be an abstract class containing any required fields that span monitoring systems
    - monitoringSystem
    - An individual metric name, type, value
    - tenantId
    - accountType (optional?)
    - tags: Map of tags that can be used as part of each monitoring system's Esper window filtering
- A `SalusEnrichedMetric` class will extend this to contain
    - resourceId
    - monitorId
    - taskId
    - zoneId (always `null` for agent metrics)
    - monitorSelectorScope
    - monitorType
    - state (originally unset)
    - excludedResourceIds (List)
    - computedStateTimestamp
    - expectedStateCounts: Map of states -> stateCount

### Window Logic

The esper window logic will have to vary based on the monitoring system.  This section only describes the behavior for Salus.  It's possible we could define a generic default handler for any other source but that is not a concern for now.

1. A task's esper query acts on any relevant `SalusEnrichedMetric` events by triggering a state calculation to occur and then inserts the resulting event into the `EntryWindow`
    - Only the most recent value is stored based on a `tenant:resource:monitor:task:zone` key
    - Any events that do not relate to a deployed query will be dropped/ignored
1. On each insert to the `EntryWindow` a `currentCount` value is inserted/updated in the `StateCountTable` to keep track of how many consecutive events have been seen with the same state for this `tenant:resource:monitor:task:zone` key
    - If the key has not been seen before the value will be 1
    - If the key has been seen but the stored state differs from the new state the value will be 1
    - If the key has been seen and the both the stored and new states are the same the stored value will be incremented by 1
1. An event arriving in `EntryWindow` will also trigger a query to determine whether the stateCount expectations have been met for a `tenant:resource:monitor:task:zone`
    - The value stored in the `StateCountTable` will be compared to the `expectedStateCounts` for the given state
    - If the expectation has been satisfied the `SalusEnrichedMetric` will be inserted into the `StateCountSatisfiedWindow`
    - `StateCountSatisfiedWindow` will still be keyed by `tenant:resource:monitor:task:zone`
1. An entry into `StateCountSatisfiedWindow` will trigger a quorum calculation to occur to determine whether quorum has been met
    - Similar logic to https://github.com/racker/salus-event-handler/pull/1/files
    - Call a method that accepts the window() from StateCountSatisfiedWindow
    - Outputs `SalusStateChange` event to UMB if state changes
        - Also saves the new state to SQL
    - It will disregard any events in the window that are `1.5*interval` behind the newest event in the window
        - i.e. any event not received within the expected polling period will be ignored
    - It will ensure the Task is "warm"
        - i.e. enough metrics have been seen to confidently output a new state
        - this is relevant for new tasks or event-engine restarts

### Output
- Sends `SalusStateChange` events to UMB
- `SalusStateChange` contains
    - eventId
    - tenantId
    - resourceId
    - monitorId
    - taskId
    - monitorType
    - monitorSelectorScope
    - observations: list of states/metrics/timestamps for each region
    - state
    - quorumCalculationTimestamp - TODO think of better name
- Task state changes are stored in SQL

## Event Handler
Currently no need for an additional event handler service.  Downstream services will take care of any delayed, flood, flapping events and group them as needed.