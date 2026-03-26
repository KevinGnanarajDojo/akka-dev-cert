package io.example.application;

import static org.junit.jupiter.api.Assertions.*;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import io.example.application.FlightConditionsAgent.ConditionsReport;
import org.junit.jupiter.api.Test;

public class FlightConditionsAgentTest extends TestKitSupport {

  private final TestModelProvider agentModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.openai.api-key = n/a")
        .withModelProvider(FlightConditionsAgent.class, agentModel);
  }

  @Test
  public void approveGoodConditions() {
    var expected = new ConditionsReport("2099-12-10-10", true);
    agentModel.fixedResponse(JsonSupport.encodeToString(expected));

    var report =
        componentClient
            .forAgent()
            .inSession("test-session")
            .method(FlightConditionsAgent::query)
            .invoke("2099-12-10-10");

    assertNotNull(report);
    assertEquals("2099-12-10-10", report.timeSlotId());
    assertTrue(report.meetsRequirements());
  }

  @Test
  public void rejectBadConditions() {
    var expected = new ConditionsReport("2099-12-10-22", false);
    agentModel.fixedResponse(JsonSupport.encodeToString(expected));

    var report =
        componentClient
            .forAgent()
            .inSession("test-session-2")
            .method(FlightConditionsAgent::query)
            .invoke("2099-12-10-22");

    assertNotNull(report);
    assertFalse(report.meetsRequirements());
  }
}
