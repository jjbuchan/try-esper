package me.jjbuchan.tryesper.tryesper.runtime;

import java.util.Map;
import me.jjbuchan.tryesper.tryesper.model.Metric;
import org.springframework.stereotype.Service;

@Service
public class MetricEvaluation {

  public static Metric populateStateCounts(Metric metric, int critical, int warning, int ok) {
    return metric.setStateCounts(Map.of(
        "critical", critical,
        "warning", warning,
        "ok", ok
    ));
  }

}
