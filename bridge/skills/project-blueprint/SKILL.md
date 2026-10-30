---
name: project-blueprint
description: "Use when working on a project: read/update its blueprint."
version: 1.0.0
---

# Project blueprint (Hermes Remote Blueprint)

Each Hermes project can have a **blueprint**: a flow diagram (Start, Feature, Flow, Decision, End boxes with arrows, like draw.io) and a task board (columns like Docs / Todo / Working / Done with cards, like Trello). The user sees and edits the same blueprint on the phone (drawer > project > blueprint icon), and changes from either side show up on the other within seconds.

## Always, when working on a project
1. **Read the blueprint first**, before planning or coding. Its `## Instructions from the user` section (when present) is the user's standing rules for THIS project (e.g. never touch LIVE, report on Telegram): follow it over any default. Its `## Waiting for the user` section lists your open questions: don't redo or guess past them; read the card's comments for the answer.
   `hermes-remote-bridge blueprint md --here` (from inside the project folder) or `blueprint md -p "<project name or id>"`.
   No blueprint: carry on normally; offer once to create one if the work is more than a quick fix.
2. Use it as the plan: the flow says how the product fits together, the board says what is done, in progress and next. Respect card checklists, priorities and due dates; ask before contradicting them.
3. **Keep it current while you work**, as you go, not only at the end:
   - starting a card: `card start <card>` (moves it to Working, assigns you, and shows "Hermes is working on this" on the phone). Pausing without finishing: `card stop <card>`.
   - finishing a step: `card check <card> <item text>`
   - done (tested): `card done <card>` (last column; clears the working badge)
   - need a decision or approval: `card ask <card> "<question>"`; want the user to check a result: `card review <card> "<what to check>" --file <path>`. Both highlight the card on the phone until answered; then STOP that card and continue with another. Plain notes: `card comment <card> "<text>" [--file <path>]`. Attach result files with `--file` (repeatable) so the user can open/download them from the card. After the user answers, `card resolve <card>` if it is still open.
   - new work found: `card add -c Todo "<title>" --item ... --tag ...` (search first, no duplicates)
   - a new feature or flow: `node add --kind feature --after "<node>" "<label>"`, link its card with `--node`
   - log decisions worth remembering: `log "<one line>"`
- A column over its card limit (`OVER THE LIMIT` in `md`): finish or move cards out of it before starting another card there.
4. Cards and nodes can be named by id or by a unique part of the title. If a name is ambiguous the command lists the ids.

## Control
- `control: shared` (default): the user leads. Update status, checklists and add found work; do not reorganise columns, delete cards/nodes or rewrite the flow without asking.
- `control: hermes`: set only when the user explicitly asks you to take over (`blueprint control hermes`). Then you own the board and diagram: plan the flow, create and order cards, move them, tidy with `layout`. The user mostly reads and makes small adjustments; respect them and never undo a change the user made. `blueprint control shared` hands it back.

## Commands
```bash
B="hermes-remote-bridge blueprint"
$B list                                  # blueprints, plus projects without one
$B md --here                             # read (Markdown: flow, board, recent activity)
$B show -p shop --json                   # full JSON (ids, coordinates)
$B create -p "<project>"                 # new blueprint for a Hermes project (asks nothing)
$B card add -p shop -c Todo "Login page" --tag auth --item "form" --item "API" --priority high --due 2026-11-30 --node Login
$B card move -p shop login Working       # card by id or unique title part; column by name or id
$B card check -p shop login form         # tick a checklist item (uncheck to undo)
$B card edit -p shop login --body "..." --assignee hermes --priority medium
$B card rm -p shop login                 # ask the user first
$B card start login | card stop login | card done login
$B card ask login "Use Stripe or PayPal?"            # highlighted until the user answers
$B card review login "Check the export" --file out/report.csv
$B card comment login "Pushed abc123" | card resolve login [<comment id>]
$B instructions                          # print the user's rules for this project (set from the phone)
$B instructions global ["<text>"|none]   # global rules for every board without its own (md marks them "(global, every board)")
$B settings                              # board settings: new_cards=top|bottom, aging_days=0|3|7|14|30 (set: settings new_cards=top)
$B settings global [new_cards=top ...]   # every board's default (also sticky_add=true|false); a board's own choice wins, key=default follows global
$B column limit Working 3                # WIP cap per column (0 or none removes it); md shows "OVER THE LIMIT"
$B node add -p shop --kind feature --after Start "Login"   # kinds: start end feature flow decision note
$B node link -p shop "Checkout" End [label]
$B node edit -p shop Login -t "Sign in" --body "note"
$B column add -p shop Review | column rename -p shop review "QA" | column rm -p shop review -c Todo
$B layout -p shop                        # tidy the diagram top to bottom
$B log -p shop "Chose Stripe over PayPal (fees)"
$B op -p shop '[{"op":"card.add","title":"A"},{"op":"card.move","id":"A","column":"Done"}]'  # batch, all or nothing
$B trash -p shop                         # moves to the trash (restorable from the app)
```
Run from the project folder and `-p` can be dropped (`--here` is implied when no project is named).
Priorities: low medium high. Assignee: me, hermes. Dates: YYYY-MM-DD. Every change is logged with who made it.

- Files on the card itself (shown as "attached:" in `md`) live in its `files` list: add one with `card.update` `"files": [...existing, "<abs path>"]` (the list is replaced, not appended). Files the phone attached are in `blueprints_dir/files/<project id>/`.

## Don't
- Don't edit the JSON files by hand (`blueprint path`); use the commands, which merge with the phone's edits.
- Don't mark a card Done before the work is verified.
- If the command is missing, the PC has an old Hermes Remote bridge: say so (update it from the phone: PC bridge > Update).
