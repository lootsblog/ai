Plan: Supervisor-Centric Agentic Architecture
This document outlines the complete flow of control and data in the newly architected system where the OrchestratorAgent acts as the central supervisor.

1. The Supervisor's Graph: The New Control Flow
The entire application logic is now managed by a single, intelligent graph within the OrchestratorAgent. This graph has three main steps (nodes) and one critical decision point (router).

graph TD
    A(Start: User Query) --> B[Node: extract_intent];
    B --> C[Node: build_query];
    C --> D{Router: _decide_next_step};
    D -- SQL is Ready --> E[Node: analyze_and_execute];
    D -- Retry Needed --> C;
    D -- Unrecoverable Error --> F(End: Send Final State);
    E --> F;

    classDef node fill:#e3f2fd,stroke:#0d47a1;
    classDef router fill:#fff3cd,stroke:#856404,stroke-width:2px;
    class B,C,E node;
    class D router;

2. Detailed Step-by-Step Query Lifecycle
Let's trace a query from the user to the final result, detailing what happens in each node.

Step 1: Intent Extraction
Active Component: OrchestratorAgent's _extract_intent_node.

What Happens: The process begins here. This node has one job: to understand the user's initial request.

Tool/Agent Called: It calls self.gemini_client.extract_intent().

Process:

The user's raw query (e.g., "delete inactive users") is sent to the Gemini AI.

The AI returns a structured intent JSON object ({ "type": "DELETE", ... }).

This node performs a crucial first check: if the AI's confidence is too low (< 0.4) or if it fails to return valid JSON, the node immediately sets an error_message in the state.

Output: The OrchestratorState is updated with the intent object or an error_message.

Step 2: Query Construction & Validation
Active Component: OrchestratorAgent's _plan_and_build_query_node.

What Happens: This node is responsible for generating and validating the SQL code. It acts as a manager calling on the QueryBuilderAgent.

Tool/Agent Called:

self.query_builder.build_sql_query()

self.query_builder.validate_query()

Process:

It first checks if an error occurred in the previous step. If so, it does nothing.

It calls the QueryBuilderAgent as a tool, passing it the intent. The QueryBuilderAgent fetches the schema and uses Gemini to generate the SQL.

The generated SQL is immediately passed to the validate_query tool.

If the query is invalid (e.g., contains DROP TABLE or has syntax errors), this node sets an error_message and a feedback_context in the state. For example: feedback_context = "The previous attempt failed. Error: Generated SQL is invalid. Please try again."

Output: The state is updated with the sql_query, query_type, and potentially an error_message and feedback_context.

Step 3: The Decision Point (The Supervisor's Brain)
Active Component: OrchestratorAgent's _decide_next_step router.

What Happens: This is the most critical part of the new architecture. It's not a processing node; it's a conditional router that inspects the current state and decides the next step for the entire system.

Process (The Diagnostic Tree):

Is there feedback_context? This means the build_query node failed but thinks the error is correctable.

Action: Check the retry_count. If it's less than 3, increment it and route the flow back to the build_query node. This creates the self-correction loop. If the limit is reached, route to END.

Is there a valid sql_query? This means the build_query node succeeded.

Action: Route the flow forward to the analyze_and_execute node.

Is there an unrecoverable error_message? (e.g., from the intent extraction step).

Action: The situation is unrecoverable. Route directly to END.

Output: A string ("build_query", "analyze_and_execute", or "end") that tells LangGraph where to go next.

Step 4: Analysis and Execution
Active Component: OrchestratorAgent's _analyze_and_execute_node.

What Happens: This node handles the final, practical steps of running the query.

Tool/Agent Called:

execute_select_query() for SELECT statements.

self.impact_analyzer.analyze_query_impact() for destructive queries.

Process:

It checks the query_type from the state.

If SELECT: It calls the execute_select_query tool and places the results into the final_result field of the state.

If DESTRUCTIVE:

It first calls the ImpactAnalysisAgent to get a risk assessment.

It then checks the requires_approval flag from the analysis.

In this refactored plan, the full approval loop is simplified: If approval is required, it sets an error message. If not, it simulates a successful execution and puts the result in the final_result field. (In a future step, this is where the create_approval_request and execute_approved_query tools would be called).

Output: The final_result field in the state is populated with either the query data or an execution status message.

Step 5: Termination and Final Response
Active Component: OrchestratorAgent's process_query method.

What Happens: Once the graph reaches the END state, control returns to the process_query method.

Process:

It takes the final state object from the graph.

It formats this state into a clean JSON response for the user, prioritizing either the final_result on success or the error_message on failure.

Output: The final, user-facing JSON object is sent back through the WebSocket.

This new architecture places all control and intelligence within the OrchestratorAgent, transforming it from a simple router into a true supervisor that can plan, execute, diagnose failures, and intelligently retry.
