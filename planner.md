Arcase Planner VS Code Extension: Detailed Plan
This plan outlines the creation of a VS Code extension, Arcase Planner, designed to streamline AI-driven project management by automating PRD generation, task list creation, and dynamic rule setup for Claude Code and Cursor.

I. Core Features & User Workflow
The extension will guide the user through a structured project initiation and management process:

Initiation (initiate-Arcase-planner Command)

CLI Invocation: The user will invoke the extension via a VS Code command, typically mapped to a CLI alias like initiate-Arcase-planner.

Welcome & Idea Prompt: Upon invocation, a VS Code Quick Pick or input box will appear, prompting: "Welcome to Arcase Planner! Let's start with your raw project idea. What do you want to build, and what problem does it solve?"

The user provides an initial, high-level project idea.

Idea Enhancement & Clarifying Questions (AI-Assisted Iteration)

AI Enhancement: The extension will send the user's raw idea to a backend Large Language Model (LLM), which will act as an "AI Project Manager." The LLM will enhance the idea by suggesting initial scopes, potential features, and identifying ambiguities.

Iterative Q&A: The LLM will then generate a series of clarifying questions based on its understanding (e.g., "Who are the target users?", "What are the primary features?", "What are the success metrics?").

Interactive Display: These questions will be presented to the user through a VS Code Webview or a sequence of Quick Pick/Input Boxes within the IDE.

Dynamic Refinement: Each user response will be fed back to the LLM, allowing it to refine its understanding and generate follow-up questions for areas that are still unclear or require more detail.

Summary & Confirmation: After a few rounds of Q&A (or when the LLM deems sufficient information gathered), the extension will display a summary of its current understanding: "Here's my current understanding of your project..." It will then ask: "Would you like to add anything else or are we ready to proceed with PRD generation?" The user can then provide more input or confirm readiness.

PRD (Product Requirements Document) Generation

User Notification: A clear message will inform the user: "Great! I'm now working on generating your comprehensive Product Requirements Document (PRD), including a high-level architecture overview and flow. This might take a moment."

LLM Generation: The extension will send all accumulated information (raw idea + Q&A history) to the LLM. The LLM, guided by an internal PRD template, will generate the content for PRD.md. While a visual architecture diagram isn't feasible with plain Markdown, the LLM will describe the architecture and flow in text format within the PRD.

File Creation & Auto-Open: The generated content will be written to PRD.md in the project's root directory. The extension will automatically open PRD.md in a VS Code editor tab for immediate review.

PRD Verification & Iteration (Human-in-the-Loop)

Direct Editing: The user can directly review and modify PRD.md within the VS Code editor.

Finalization Signal: The extension will provide a clear mechanism (e.g., a button in a dedicated sidebar view or a custom VS Code command) for the user to signal "PRD Finalized."

Optional AI Review: If the user makes edits to the PRD, the extension could optionally prompt: "PRD has been modified. Would you like the AI to review it for consistency or generate new clarifying questions based on your changes?" (Initially, this might be simplified to just waiting for the "Finalized" signal).

Task List & Agent Rules Generation

User Notification: Once the PRD is finalized, a message will inform the user: "Excellent! Now that the PRD is finalized, I'm generating a detailed, phase-wise task list for your AI agent to follow, along with the necessary context and rules for both Claude Code and Cursor."

LLM Generation: The extension will send the finalized PRD.md content to the LLM.

Task List: The LLM will generate TASK_LIST.md content, breaking down the project into granular, phased tasks with dependencies.

Agent Rules: The LLM will generate the necessary AI agent rule content:

For Claude Code: CLAUDE.md content, including the core system prompt and @ imports for relevant project files (PRD.md, TASK_LIST.md, CONTEXT.md, etc.).

For Cursor: .cursor/index.mdc and potentially other .cursor/rules/*.mdc files (e.g., memory.mdc, error_handling.mdc), embedding the system prompt and @ references to project files.

File Creation: The generated content will be written to their respective file paths within the workspace.

Core Meta-Files: The extension will ensure that CONTEXT.md, PROGRESS_LOG.md, DEBUG_LOG.md, and APPROVAL_QUEUE.md are created (initially empty) in the project root if they don't already exist.

Platform Detection & Setup Confirmation

Automatic Detection: The extension will attempt to detect the active AI agent environment (Cursor or Claude Code). This can be done by checking for specific environment variables, the presence of unique IDE configuration files (.cursor/ directory), or other heuristics.

Confirmation Message: A confirmation message will be displayed: "Setup complete! Your project plan (PRD.md, TASK_LIST.md) and AI agent rules (CLAUDE.md / .cursor/rules) are now configured. You can view your tasks in the 'Arcase Tasks' sidebar."

Guidance: "To begin agentic development, simply interact with your AI agent (Claude Code CLI / Cursor chat) in this directory. It will now follow the new rules and context files."

Ongoing Task View & Project Monitoring (VS Code Sidebar)

Arcase Tasks Sidebar: A dedicated VS Code sidebar view will be implemented to provide a live, readable overview of the project's status.

Task Display: It will parse TASK_LIST.md and display tasks in a hierarchical, phase-wise structure.

Progress Visualization: Tasks will be marked with clear visual indicators (e.g., green checkmark for [X], different icons for in-progress or blocked tasks if the format allows).

File Quick Access: Buttons or links will allow users to quickly open PRD.md, CONTEXT.md, DEBUG_LOG.md, and APPROVAL_QUEUE.md in editor tabs for review or direct manual editing.

User Interaction for Approvals:

The extension will continuously monitor APPROVAL_QUEUE.md for new approval requests from the AI agent.

When a new request is detected, a prominent VS Code notification will pop up.

The notification can include a direct link to APPROVAL_QUEUE.md for the user to review and provide their "Approve" / "Deny" response by editing the file. A dedicated "Review Approvals" button in the sidebar could also be provided.

Refresh Mechanism: A refresh button in the sidebar will allow users to manually re-parse and update the displayed task list and logs.

II. Technical Architecture & Implementation Details
The solution will primarily involve two interacting components: a VS Code Extension (TypeScript/JavaScript) for the UI and user interaction, and a Python Backend for the core AI logic and file management.

VS Code Extension (TypeScript/JavaScript)

Main Logic (extension.ts): This file will contain the primary activation logic for the extension.

Command Registration: Register the initiate-Arcase-planner command.

Webview & Quick Pick Management: Handle the creation and dismissal of Webviews for interactive Q&A and Quick Picks for simple inputs.

Sidebar View (TreeView): Implement the Arcase Tasks sidebar to display parsed task lists and quick-access file links. This will involve using vscode.TreeDataProvider.

File System Watchers: Use vscode.workspace.createFileSystemWatcher to monitor changes to PRD.md, TASK_LIST.md, APPROVAL_QUEUE.md, and other relevant files. These watchers will trigger UI updates or notifications.

Backend Communication: This is crucial. The VS Code extension will communicate with the Python backend.

Option A (Simpler, Initial): Child Process with Std-IO: Use Node.js child_process.spawn to run the Python backend script. Input can be passed via stdin to the Python script, and output (like AI responses, task updates) can be read from stdout. This might involve using a simple "protocol" (e.g., JSON lines) over stdout.

Option B (Robust, Scalable): Local HTTP/WebSocket Server: The Python backend could expose a lightweight HTTP server (e.g., using Flask or FastAPI) or a WebSocket server. The VS Code extension would then make fetch requests (for HTTP) or establish WebSocket connections (for real-time updates) to this local server. This is generally preferred for more complex, long-running interactions.

Python Backend (arc_backend.py)

LLM Integration: This will be the core AI brain.

Use the official client libraries for your chosen LLMs: google-generativeai (for Gemini), anthropic (for Claude), or openai (for OpenAI models).

It will handle prompt engineering for each stage: idea enhancement, clarifying questions, PRD generation, task list breakdown, and agent rule generation.

It will manage the conversation history for the iterative Q&A.

File Management: Robust functions for creating, reading, writing, and appending to the project's Markdown files (PRD.md, TASK_LIST.md, CONTEXT.md, PROGRESS_LOG.md, DEBUG_LOG.md, APPROVAL_QUEUE.md).

Structured Output Parsing: After LLM generation, the backend will parse the output to ensure it matches the expected Markdown format for PRD.md and TASK_LIST.md. If the LLM generates JSON, it will parse that.

Platform Detection Logic:

Implement logic to detect the active environment:

Check for the presence of the .cursor/ directory.

Check for VS Code environment variables that might indicate Cursor (e.g., VSCODE_CWD might provide clues, though more direct detection might be needed, possibly through the VS Code API if Cursor exposes it).

Check for Claude Code specific environment variables (e.g., ANTHROPIC_API_KEY) or the existence of the claude executable in the PATH.

This detection will inform which set of agent rules to write.

Rule Generation Logic: Dynamically generate the precise Markdown content for CLAUDE.md and Cursor's .mdc files, embedding the system prompt and @[filename] references to the project's meta-files.

III. Project Workspace File Structure
The Arcase Planner will primarily operate within and create files in the user's VS Code workspace root.

my_awesome_project/
├── .vscode/                 # Standard VS Code configuration
│   └── launch.json          # Optional: For debugging the extension if it runs as a separate process
├── .cursor/                 # **Cursor-specific AI agent rules**
│   ├── index.mdc            # Main project rule (contains core AI agent directives)
│   └── rules/
│       ├── memory.mdc       # Rule for agent's long-term memory (points to CONTEXT.md)
│       └── error_handling.mdc # Rule for how agents handle errors (points to DEBUG_LOG.md, APPROVAL_QUEUE.md)
│       └── ...              # Other modular rules based on project needs
├── CLAUDE.md                # **Claude Code-specific AI agent rules** (contains core AI agent directives)
├── PRD.md                   # Product Requirements Document (generated by AI, edited by user)
├── TASK_LIST.md             # Detailed, phase-wise task list (generated by AI, used by agents)
├── CONTEXT.md               # Agent's long-term memory / knowledge bank (updated by agents)
├── PROGRESS_LOG.md          # Log of completed tasks and significant milestones (updated by agents)
├── DEBUG_LOG.md             # Log of errors, debugging attempts, and agent's proposed fixes (updated by agents)
├── APPROVAL_QUEUE.md        # File for AI agent to post approval requests, and user to respond
├── src/                     # Your project's actual source code (where agents will write code)
├── tests/                   # Your project's test files
├── .git/                    # Standard Git repository for version control
└── README.md                # Your project's main README

IV. Key Implementation Considerations
LLM Token Management: For complex PRDs and extensive Q&A, ensure the LLM prompts are optimized to stay within context window limits. This might involve summarizing previous turns before sending to the LLM.

User Experience (UX): Design the Webview/Quick Pick interactions to be as smooth and intuitive as possible. Clear instructions and feedback are crucial.

Error Handling (Internal): The extension needs robust error handling for LLM API failures, network issues, file system problems, and unexpected LLM responses.

Modularity: Keep the Python backend functions modular so they can be tested independently and easily adapted.

Security: Be mindful of API key handling (use VS Code secrets storage or environment variables, not hardcoding). Ensure the Python backend only operates within the project directory.

Testing: Thoroughly test each stage of the workflow, especially the LLM interactions and file manipulations.

Version Control for Meta-Files: Emphasize to the user that PRD.md, TASK_LIST.md, CONTEXT.md, and the agent rule files (CLAUDE.md, .cursor/) should be committed to Git. This ensures project history and agent context are preserved across team members and sessions.

V. LLM Prompts and Manual Flow (Using Gemini 2.5 Flash)
This section outlines the specific prompts the Python backend will use for Gemini 2.5 Flash, along with instructions for a manual flow if you were to use a chat-based LLM interface directly.

Model to use for Prompts: gemini-2.5-flash

A. Idea Enhancement & Clarifying Questions Stage
1. Backend Prompt Structure (Initial Request)

Role: system

Content: "You are an AI Project Manager Assistant. Your goal is to help a user define a project idea by enhancing their initial concept and asking a series of clarifying questions. Focus on identifying the core problem, target users, main features, and success metrics. Ask one concise question at a time. If the user provides a comprehensive answer, move to the next logical question. If their answer is vague, ask follow-up questions to drill down. Once you believe you have a good understanding, ask for confirmation to proceed to PRD generation. Structure your questions to build a clear mental model of the project."

User Input (first turn): The raw idea provided by the user (e.g., "I want to build a simple habit tracker app for personal use.").

2. Backend Prompt Structure (Subsequent Turns - Iterative Q&A)

Role: system

Content: "You are an AI Project Manager Assistant, continuing to refine a project idea. Your goal is to ask clarifying questions based on the current conversation context to gather all necessary details for a comprehensive PRD. Focus on one concise question at a time. If the user's last response was comprehensive, move to the next logical question. If it was vague, ask a follow-up to clarify. Once you feel you have a solid understanding of the project's problem, users, core features, and success metrics, conclude by asking the user to confirm readiness for PRD generation. Do not generate the PRD yet."

User Input: The full conversation history (alternating user and AI turns, starting with the raw idea).

Manual Flow Prompt (for a fresh chat session):

Hello AI! I want you to act as an AI Project Manager. My goal is to define a new software project.

Here's my raw idea: "[User's raw project idea, e.g., 'I want to build an app that helps me manage my daily tasks.']"

Your task is to enhance this idea by asking me clarifying questions, one at a time. Focus on getting details about:
1. The core problem it solves.
2. The specific target users.
3. The main features and functionality.
4. How we will measure its success (success metrics/KPIs).
5. Any initial thoughts on the technology stack or constraints.

Start by asking your first clarifying question to enhance my idea.

(Continue the conversation, providing concise answers to each question. When you feel the AI has enough information, respond with something like: "I think we have enough information now. Are you ready for me to generate the PRD?")

B. PRD (Product Requirements Document) Generation Stage
Role: system

Content: "You are an expert Technical Product Manager and Software Architect. Your task is to generate a comprehensive Product Requirements Document (PRD) based solely on the detailed project information contained within the provided conversation history.

PRD Structure Requirements:

Project Title & Overview: Clear and concise.

Problem Statement: What specific problem does this project solve?

Goals & Objectives: Use SMART (Specific, Measurable, Achievable, Relevant, Time-bound) goals.

Target Audience / User Personas: Who are the primary users? Describe them briefly.

Key Features: List and briefly describe the core functionalities.

Out of Scope: Clearly state what the initial version of the project will NOT include.

High-Level Architecture & Flow: Describe the main components (frontend, backend, database, APIs) and how data/users flow through the system. Do not draw diagrams, but use clear text descriptions.

Technical Considerations: Any initial thoughts on languages, frameworks, or key technologies.

Dependencies: Any external systems or components this project relies on.

Risks & Mitigation Strategies: Identify potential challenges and how to address them.

Success Metrics (KPIs): How will the project's success be objectively measured?

Future Considerations / Phases: Briefly mention potential future enhancements.

Ensure the PRD is well-structured, clear, concise, and uses Markdown formatting for readability. Generate ONLY the PRD content in Markdown, with no additional conversational text or preamble."

User Input: The entire conversation history from the "Idea Enhancement & Clarifying Questions" stage (up to the point of user confirmation for PRD generation).

Manual Flow Prompt:

I have defined a project idea through a series of questions and answers. My goal now is to generate a comprehensive Product Requirements Document (PRD) based on all the information we've discussed.

Here is the full conversation history that contains all the details:
--- CONVERSATION HISTORY START ---
[Paste the complete conversation history from the previous stage, including your raw idea and all Q&A turns. Make sure to include the final confirmation from you.]
--- CONVERSATION HISTORY END ---

Based on this conversation history, generate a comprehensive PRD using the following structure. Make sure to describe the architecture and flow in text, as I cannot include diagrams.

**PRD Structure:**
* **Project Title & Overview:**
* **Problem Statement:**
* **Goals & Objectives:** (SMART goals)
* **Target Audience / User Personas:**
* **Key Features:**
* **Out of Scope:**
* **High-Level Architecture & Flow:**
* **Technical Considerations:**
* **Dependencies:**
* **Risks & Mitigation Strategies:**
* **Success Metrics (KPIs):**
* **Future Considerations / Phases:**

Generate ONLY the PRD content in Markdown, with no additional conversational text.

C. Task List & Agent Rules Generation Stage
Role: system

Content: "You are an expert Software Architect and AI Agent Configuration Specialist. Your task is to perform two actions based on the provided Product Requirements Document (PRD):

Action 1: Generate a Detailed, Phase-wise Task List (TASK_LIST.md)

Break down the PRD into logical phases (e.g., 'Planning', 'Environment Setup', 'Core Feature: [X]', 'Testing', 'Deployment').

Within each phase, create granular, actionable tasks that an AI agent can execute independently.

Use Markdown checkboxes [ ] for tasks.

Indicate explicit dependencies using _Depends on:_ [Task X.Y] or similar directly below the dependent task.

Provide brief sub-details for complex tasks using nested bullet points or _Sub-task:_ descriptions.

Action 2: Generate AI Agent Configuration Rules

Generate two distinct sets of rules, ready for direct file writing:

Claude Code Rules (CLAUDE.md content):

Start with the YAML frontmatter and --- for clarity.

Include a clear system prompt for a general AI Dev Agent (e.g., 'You are an AI-powered Senior Software Engineer and Project Lead...').

Reference PRD.md, TASK_LIST.md, CONTEXT.md, PROGRESS_LOG.md, DEBUG_LOG.md, APPROVAL_QUEUE.md using the exact @filename syntax as Claude Code expects.

Include directives for the agent on:

Adhering strictly to the PRD and Task List.

Maintaining context via CONTEXT.md (read before task, append after significant learning/decision).

Logging all significant actions/outcomes to PROGRESS_LOG.md and errors/diagnoses to DEBUG_LOG.md.

CRITICAL: How to handle errors and seek user approval via APPROVAL_QUEUE.md. Emphasize that the agent MUST halt and wait for explicit approval for significant changes to existing, working code or complex error resolutions. Provide the exact APPROVAL_QUEUE.md format for the agent to use.

Updating TASK_LIST.md by replacing [ ] with [X] for completed tasks.

Prioritizing stability, avoiding unapproved changes, and being cautious.

Explaining its thought process and next steps in every interaction.

Cursor Rules (.cursor/index.mdc content):

Start with the --- YAML frontmatter (e.g., ruleType: "Always", description: "Core directives...").

Include a clear system prompt for the AI Dev Agent, mirroring the Claude Code prompt's intent.

Reference PRD.md, TASK_LIST.md, CONTEXT.md, etc., using the exact @filename syntax as Cursor expects.

The directives for agent behavior regarding PRD adherence, task execution, context management, logging, CRITICAL error handling and approval workflow via APPROVAL_QUEUE.md, and progress updates should be consistent with the Claude Code rules.

Ensure the APPROVAL_QUEUE.md format is also specified for Cursor's agent.

Output Format:
Provide the Task List, Claude Code Rules, and Cursor Rules clearly separated by Markdown headings. Ensure file paths are relative to the project root. The content for CLAUDE.md and .cursor/index.mdc should be ready to be written directly to those files by the backend. Do not include any other conversational text or preamble/postamble outside these three formatted sections."

User Input: The content of the finalized PRD.md.

Manual Flow Prompt:

I have a finalized Product Requirements Document (PRD). My goal is to generate two things based on this PRD:

1.  A detailed, phase-wise task list that an AI agent can follow.
2.  Specific AI agent configuration rules for both Claude Code and Cursor, which will guide the AI agents during development.

Here is the complete PRD content:
--- PRD START ---
[Paste the complete, finalized content of PRD.md here]
--- PRD END ---

Now, generate the content for both the Task List and the Agent Rules.

**For the Task List (`TASK_LIST.md`):**
* Break down the PRD into logical phases (e.g., 'Planning', 'Environment Setup', 'Core Feature: [X]', 'Testing', 'Deployment').
* Within each phase, create granular, actionable tasks.
* Use Markdown checkboxes `[ ]` for tasks.
* Indicate explicit dependencies using `_Depends on:_ [Task X.Y]` or similar directly below the dependent task.
* Provide brief sub-details for complex tasks using nested bullet points or `_Sub-task:_` descriptions.

**For the Agent Rules:**
* **Claude Code Rules (`CLAUDE.md` content):**
    * Include a clear system prompt for a general AI Dev Agent.
    * Reference `PRD.md`, `TASK_LIST.md`, `CONTEXT.md`, `PROGRESS_LOG.md`, `DEBUG_LOG.md`, `APPROVAL_QUEUE.md` using `@filename` syntax.
    * Include directives for the agent on:
        * Adhering to the PRD and Task List.
        * Maintaining context via `CONTEXT.md`.
        * Logging to `PROGRESS_LOG.md` and `DEBUG_LOG.md`.
        * **CRITICAL: How to handle errors and seek user approval via `APPROVAL_QUEUE.md` (Do NOT proceed without explicit approval for significant changes/fixes to existing code). Provide the exact format for the approval request in `APPROVAL_QUEUE.md`.**
        * Updating `TASK_LIST.md` with `[X]` for completion.
        * Prioritizing stability over unapproved changes.
        * Explaining thought processes.
* **Cursor Rules (`.cursor/index.mdc` content):**
    * Similar to Claude Code rules, but adapted for Cursor's `.mdc` format.
    * Include `---` YAML frontmatter with `ruleType: "Always"` and a `description`.
    * Reference `PRD.md`, `TASK_LIST.md`, `CONTEXT.md`, etc., using `@filename` syntax within the Markdown content.
    * The core directives for the AI Dev Agent should be identical in intent to the Claude Code rules, including the **CRITICAL error handling and approval workflow via `APPROVAL_QUEUE.md`**. Ensure the `APPROVAL_QUEUE.md` format is also specified here for Cursor's agent.

Present the Task List, Claude Code Rules, and Cursor Rules clearly separated by Markdown headings, as if they were distinct sections of an output. Ensure all file paths are relative to the project root (e.g., `PRD.md`, not `/path/to/project/PRD.md`).
