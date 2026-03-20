package io.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.FunctionTool;

/*
 * The flight conditions agent is responsible for making a determination about the flight
 * conditions for a given day and time. You will need to clearly define the success criteria
 * for the report and instruct the agent (in the system prompt) about the schema of
 * the results it must return (the ConditionsReport).
 *
 * Also be sure to provide clear instructions on how and when tools should be invoked
 * in order to generate results.
 *
 * Flight conditions criteria don't need to be exhaustive, but you should supply the
 * criteria so that an agent does not need to make an external HTTP call to query
 * the condition limits.
 */

@Component(id = "flight-conditions-agent")
public class FlightConditionsAgent extends Agent {

    record ConditionsReport(String timeSlotId, Boolean meetsRequirements) {
    }

    private static final String SYSTEM_MESSAGE = """
            You are an agent responsible for evaluating flight conditions for a flight training school.
            Your job is to determine whether weather conditions are safe for a student training flight
            at the requested time slot. \
            
            PROCESS:
            1. Use the getWeatherForecast tool to retrieve conditions for the given time slot ID.
            2. Evaluate the forecast against safety criteria below.
            3. If the time slot is too far in the future to get a reliable forecast, conditionally
            approve it (meetRequirements = true). \
            
            SAFETY CRITERIA:
            - Wind speed must be below 25 knots.
            - Visibility must be at least 3 statute miles
            - No severe weather (snow, hail, sleet, thunderstorms, heavy rain)
            - Temperature must be above -10C and below 40C \
            
            RESPONSE:
            You must respond with a JSON object containing:
            - timeSlotId: the time slot ID that was evaluated
            - meetsrequirements: true if conditions are safe or cannot yet predicted, false if conditions violate
            ANY of the safety criteria.\
            """.stripIndent();

    public Effect<ConditionsReport> query(String timeSlotId) {
        return effects().systemMessage(SYSTEM_MESSAGE)
                .userMessage("Evaluate flight conditions for time slot: " + timeSlotId)
                .responseAs(ConditionsReport.class)
                .thenReply();
    }

    /*
     * You can choose to hard code the weather conditions for specific days or you
     * can actually
     * communicate with an external weather API. You should be able to get both
     * suitable weather
     * conditions and poor weather conditions from this tool function for testing.
     */
    @FunctionTool(description = "Queries the weather conditions as they are forecasted based on the time slot ID of the training session booking")
    private String getWeatherForecast(String timeSlotId) {
        int hour = Integer.parseInt(timeSlotId.substring(timeSlotId.lastIndexOf('-') + 1));

        if (hour > 6 && hour < 18) {
            return "Clear skies, wind 10 knots, visibility 10 miles, temperature around 15C";
        }
        else{
            return "Heavy snowstorm, wind 60 knots, visibility 0.2 miles, temperature around 2C";
        }
    }
}
