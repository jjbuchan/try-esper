package me.jjbuchan.tryesper.tryesper.compiler;

import static org.apache.commons.lang.RandomStringUtils.randomAlphabetic;
import static org.apache.commons.lang.RandomStringUtils.randomAlphanumeric;
import static org.apache.commons.lang.RandomStringUtils.randomNumeric;

import com.espertech.esper.common.client.EPCompiled;
import com.espertech.esper.common.client.configuration.Configuration;
import com.espertech.esper.common.client.fireandforget.EPFireAndForgetPreparedQuery;
import com.espertech.esper.common.client.fireandforget.EPFireAndForgetQueryResult;
import com.espertech.esper.common.client.util.NameAccessModifier;
import com.espertech.esper.compiler.client.CompilerArguments;
import com.espertech.esper.compiler.client.EPCompileException;
import com.espertech.esper.compiler.client.EPCompilerProvider;
import com.espertech.esper.runtime.client.EPDeployException;
import com.espertech.esper.runtime.client.EPDeployment;
import com.espertech.esper.runtime.client.EPRuntime;
import com.espertech.esper.runtime.client.EPRuntimeProvider;
import com.espertech.esper.runtime.client.EPStatement;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import me.jjbuchan.tryesper.tryesper.model.Metric;
import me.jjbuchan.tryesper.tryesper.runtime.MetricEvaluation;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class Initializer {

  private EPRuntime runtime;
  private Configuration config;

  public Initializer() {
    this.config = new Configuration();
    this.config.getCommon().addEventType(Metric.class);
    this.config.getCommon().addImport(MetricEvaluation.class);
    this.runtime = EPRuntimeProvider.getDefaultRuntime(this.config);

    createWindows();
    createWindowTransitionLogic(); // need to ensure they're created in a certain order to account for dependencies / race conditions?
    addTasks();
    sendEvents();
    lookInWindow();
  }

  private void createWindows() {
    final String entryWindow = "create window EntryWindow.std:unique(tenantId, resourceId, monitorId, taskId, monitorZone) as "
        + "select * from Metric";

    // potentially need to create a schema for any queries to this table
    final String stateCountTable = "create table StateCountTable ("
        + "tenantId string primary key,"
        + "resourceId string primary key,"
        + "monitorId string primary key,"
        + "taskId string primary key,"
        + "monitorZone string primary key,"
        + "state string,"
        + "currentCount int"
        + ")";

    final String stateCountSatisfiedWindow = "create window StateCountSatisfiedWindow.std:unique(tenantId, resourceId, monitorId, taskId, monitorZone) as "
        + "select * from Metric";

    final String quorumStateWindow = "create window QuorumStateWindow.std:unique(tenantId, resourceId, monitorId, taskId) as "
        + "select * from Metric";

    compileAndDeploy(entryWindow).addListener((newData, oldData, stmt, rt) ->
        log.info("Saw new event in EntryWindow={}", stmt));

    compileAndDeploy(stateCountTable).addListener((newData, oldData, stmt, rt) ->
        log.info("Saw new event in count table={}", stmt));

    compileAndDeploy(stateCountSatisfiedWindow);

    compileAndDeploy(quorumStateWindow);
  }

  /**
   * TODO: try and ensure ordering of these queries so the table logic runs before the new value is utilized
   * http://esper.espertech.com/release-8.1.0/reference-esper/html/apicompiler.html#apicompiler-deploymentorder
   * http://esper.espertech.com/release-8.1.0/reference-esper/html/apicompiler.html#apicompiler-concepts-dependencyresolve
   */
  private void createWindowTransitionLogic() {
    String updateStateCountLogic = ""
        + "on EntryWindow as ew "
        + "merge into StateCountTable as cct "
        + "where "
        + "   ew.tenantId = cct.tenantId and "
        + "   ew.resourceId = cct.resourceId and "
        + "   ew.monitorId = cct.monitorId and "
        + "   ew.taskId = cct.taskId and "
        + "   ew.monitorZone = cct.monitorZone "
        + "when matched and ew.state = cct.state "
        + "   then update set cct.currentCount = cct.currentCount + 1"
        // should we prevent currentCount from getting too high?  What happens when it maxes out?
        + "when matched and ew.state != cct.state "
        + "   then update set cct.currentCount = 1"
        + "when not matched "
        + "   then insert (tenantId, resourceId, monitorId, taskId, monitorZone, state, currentCount) "
        + "        select tenantId, resourceId, monitorId, taskId, monitorZone, state, 1";

    compileAndDeploy(updateStateCountLogic).addListener((newData, oldData, stmt, rt) ->
        log.info("Saw new event in count table, {}", newData));

    String stateCountSatisfiedLogic = "@Audit "
        + "insert into StateCountSatisfiedWindow "
        + "select ew.* from EntryWindow as ew "
        + "   where "
        + "       ew.stateCounts(ew.state) = 1 or" // this step might not actually help with any optimization
        + "       (select cct.currentCount from StateCountTable as cct "
        + "          where "
        + "              ew.tenantId = cct.tenantId and "
        + "              ew.resourceId = cct.resourceId and "
        + "              ew.monitorId = cct.monitorId and "
        + "              ew.taskId = cct.taskId and "
        + "              ew.monitorZone = cct.monitorZone"
        + "       ) >= ew.stateCounts(ew.state)"; // can we guarantee order of updates better?

    compileAndDeploy(stateCountSatisfiedLogic).addListener((newData, oldData, stmt, rt) ->
        log.info("Saw new event in state count satisfied window, {}", newData));

    // need to disregard old events and only care about new ones then see if quorum has been met
    // can do timestamp comparison in here?  would have to get newest event in window (sort) and then filter all others that dont match
    // then pass those to quorum method?
    String quorumStateLogic = ""
        + "insert into QuorumstateWindow "
        + "select csw.* from StateCountSatisfiedWindow as csw "
        + "";
  }

  private void lookInWindow() {
    final String entryWindowEvents = "@name('output') select count(*) as uniqueTaskZoneCombo from EntryWindow";
    EPFireAndForgetQueryResult result = onDemandQuery(entryWindowEvents);
    log.info("Result of on demand query is {}", result.getArray()[0].getUnderlying());

    final String stateCountTable = "@name('output') select tenantId, resourceId, monitorId, "
        + "taskId, monitorZone, state, currentCount from StateCountTable";
    EPFireAndForgetQueryResult result2 = onDemandQuery(stateCountTable);
    log.info("StateCountTable contains the following values");
    for (int i =0; i< result2.getArray().length; i++) {
      Map<String, String> entry = (Map<String, String>) result2.getArray()[i].getUnderlying();
      log.info("{}. {}:{}:{}:{}:{} {}={}", i,
          entry.get("tenantId"), entry.get("resourceId"), entry.get("monitorId"),
          entry.get("taskId"), entry.get("monitorZone"), entry.get("state"),
          entry.get("currentCount"));
    }
  }

  private void addTasks() {
    String query = ""
        + "@name('my-statement') "
        + "insert into EntryWindow "
        + "select MetricEvaluation.populateStateCounts(metric, 1, 2, 3) from Metric("
        + "tenantId='my-tenant' and "
        + "monitorScope='remote' and "
        + "monitorType='http' and "
        + "resourceId not in (excludedResourceIds) and "
        + "tags('os')='linux' and tags('metric')='something') metric";

    compileAndDeploy(query).addListener((newData, oldData, stmt, rt) -> {
      String tenantId = (String) newData[0].get("tenantId");
      String resourceId = (String) newData[0].get("resourceId");
      String monitorId = (String) newData[0].get("monitorId");
      log.info(String.format("Inserting event into EntryWindow TenantId: %s, Resource: %s, MonitorId: %s", tenantId, resourceId, monitorId));
    });
  }

  public void sendEvents() {
    int metricRange = 10;
    Metric m1 = buildMetric();
    Metric m2 = buildMetric();

    // send {metricRange} dupes of m1
    // send {metricRange} dupes of m2
    // send {metricRange} uniques of a valid metric
    // provide a random state for each to test the state count table logic
    IntStream.range(0, metricRange).forEach(i -> {
      log.info("sending valid metric 1-{}", i);
      runtime.getEventService().sendEventBean(m1.setState(generateState()), "Metric");
      log.info("sending valid metric 2-{}", i);
      runtime.getEventService().sendEventBean(m2.setState(generateState()), "Metric");
      runtime.getEventService().sendEventBean(buildMetric().setState(generateState()), "Metric");
    });

    // then send 3 invalid metric and 1 valid

    log.info("sending excluded resource metric 1");
    runtime.getEventService().sendEventBean(buildExcludedResourceIdMetric(), "Metric");
    log.info("sending invalid tenant metric 1");
    runtime.getEventService().sendEventBean(buildInvalidTenantMetric(), "Metric");
    log.info("sending partial tag match 1");
    runtime.getEventService().sendEventBean(buildMetricTagsPartialMatched(), "Metric");
    log.info("sending tags match 1");
    runtime.getEventService().sendEventBean(buildMetricExtraTags(), "Metric");

    // total unqiue valid metrics should be {metricRange} + 3
  }

  private EPStatement compileAndDeploy(String epl) {
    try {
      CompilerArguments args = new CompilerArguments(config);
      args.getPath().add(runtime.getRuntimePath());
      args.getOptions().setAccessModifierNamedWindow(env -> NameAccessModifier.PUBLIC); // All named windows are visibile
      args.getOptions().setAccessModifierTable(env -> NameAccessModifier.PUBLIC);
      EPCompiled compiled = EPCompilerProvider.getCompiler().compile(epl, args);
      EPDeployment deployment = runtime.getDeploymentService().deploy(compiled);
      return deployment.getStatements()[0];
    } catch (EPCompileException e) {
      log.error("Failed to compile query={}", epl);
      throw new RuntimeException(e);
    } catch (EPDeployException ex) {
      log.error("Failed to deploy query={}", epl);
      throw new RuntimeException(ex);
    }
  }

  private EPFireAndForgetQueryResult onDemandQuery(String epl) {
    try {
      CompilerArguments args = new CompilerArguments(config);
      args.getPath().add(runtime.getRuntimePath());
      args.getOptions().setAccessModifierNamedWindow(env -> NameAccessModifier.PUBLIC);
      args.getOptions().setAccessModifierTable(env -> NameAccessModifier.PUBLIC);
      EPCompiled compiled = EPCompilerProvider.getCompiler().compileQuery(epl, args);
      EPFireAndForgetPreparedQuery onDemandQuery = runtime.getFireAndForgetService().prepareQuery(compiled);
      return onDemandQuery.execute();
    } catch (EPCompileException e) {
      log.error("Failed to compile query={}", epl);
      throw new RuntimeException(e);
    }
  }

  private static Metric buildMetric() {
    return buildMetric("my-tenant", randomAlphanumeric(10));
  }

  private static Metric buildExcludedResourceIdMetric() {
    return buildMetric("my-tenant", "r1");
  }

  private static Metric buildInvalidTenantMetric() {
    return buildMetric(randomAlphanumeric(10), randomAlphanumeric(10));
  }

  private static Metric buildMetricTagsPartialMatched() {
    return buildMetric("my-tenant", randomAlphanumeric(10), Map.of(
        "os", "windows",
        "metric", "something"));
  }

  private static Metric buildMetricExtraTags() {
    return buildMetric("my-tenant", randomAlphanumeric(10), Map.of(
        "os", "linux",
        "metric", "something",
        "another", "one"));
  }


  private static Metric buildMetric(String tenantId, String resourceId) {
    return buildMetric(tenantId, resourceId, null);

  }

  private static Metric buildMetric(String tenantId, String resourceId, Map<String, String> tags) {
    return new Metric()
        .setTenantId(tenantId != null ? tenantId : randomAlphanumeric(10))
        .setResourceId(resourceId != null ? resourceId : randomAlphanumeric(10))
        .setMonitorId(randomAlphanumeric(10))
        .setTaskId(randomAlphanumeric(10))
        .setMonitorType("http")
        .setMonitorScope("remote")
        .setMetricName(randomAlphabetic(5))
        .setValue(randomNumeric(3))
        .setTags(tags != null ? tags : Map.of(
            "os", "linux",
            "metric", "something"))
        .setExcludedResourceIds(List.of("r1", "r2", "r3"))
        .setMonitorZone("dfw")
        .setState(generateState());
  }

  private static String generateState() {
    return List.of("critical", "ok").get(new Random().nextInt(2));
  }

}
