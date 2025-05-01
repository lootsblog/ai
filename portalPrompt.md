Goal:
Design a modern, sleek, and smooth user interface for a survey-based web portal aimed at tracking user content preferences. The portal will simulate real user experiences by showing variations in content presentation styles and gathering data on user engagement preferences. This is a frontend-only prototype for now (mock data only, no backend integration).

🧾 Application Structure
1. User Onboarding Screen (Start Page)
Two input fields:

User Name (text)

User ID (numeric or alphanumeric)

A “Start Survey” button that takes the user to the main survey sets.

Clean, welcoming layout with modern typography and subtle animations.

2. Survey Flow (Set-based Navigation)
Each Set corresponds to one survey page. For the prototype, create 2–3 sets using mock content.

➕ Each Set Includes:
Three Rows representing Content Presentation Styles:

Simple

Attractive

GenZ

Each Row has:

1 Title Card (Topic Bait): This is a stylized heading/label of the section.

5 Content Cards, each with:

A title (e.g., “Squid Games”)

A visual/graphic placeholder

A Content Bait (description) that varies slightly in tone/style per row (style reflects the row's category).

A Radio Button for selection under each card.

Users can compare the same content across different stylistic rows.

✅ Selection Rules (Must be enforced with UI logic):
User must select at least one content bait per topic (i.e., 1 choice among the 3 rows for each of the 5 content items).

User must select one topic bait (i.e., one title per row).

Only after satisfying all conditions, the “Next Set” button becomes active.

3. Navigation & Validation
Progress bar or stepper at the top (e.g., “Set 1 of 3”).

Disabled Next button until validation passes.

Optional: Warning indicators if any section is incomplete.

🧪 Mock Data Example (Set 1)
Topic Bait Titles:

Simple: "Top Picks This Week"

Attractive: "🔥 Trending Now"

GenZ: "Vibe Check – What's Poppin?"

Content Baits for “Squid Games”:

Simple: “A South Korean survival drama where contestants face deadly games.”

Attractive: “A gripping thriller that hooks you with intense visuals and plot twists.”

GenZ: “This show is LIT 🔥 - Korean hunger games but crazier!”

Repeat similar variations for other content items: “Stranger Things,” “Money Heist,” etc.

🎨 UI Design Preferences
Style: Clean, responsive,Desktop-first. Soft shadows, rounded corners, subtle transitions.

Color Scheme: Neutral base with accent colors per row (e.g., blue for Simple, red for Attractive, neon green/purple for GenZ).

Typography: Use modern sans-serif fonts like Inter or Poppins.

Interactivity: Hover effects, smooth radio selection animations, and button transitions.
Create UI in react and jS , Dont use Next . Use mock data for example purpose now , And each content will also have an image so use placeholders for that for now 
