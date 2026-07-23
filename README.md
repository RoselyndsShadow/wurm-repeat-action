# wurm-repeat-action
A client-side mod for Wurm Unlimited that lets you repeat your last action with one command.

## Features
- Lets you assign a key to repeat your last performed action.
- Discovers when an item is consumed, then searches your inventory for an item of the same type as the consumed item and activates it for the next use.
- Has an ignore list.
- Easy cut down + chop up after the first time.
- Memory is based on current held tool, switch tools to clear your cache.
- Works on any server (including offline/singleplayer).
- Lightweight and easy to use.

## Installation

1. Download the latest release.
2. Place `repeataction.properties` inside your `mods/` folder, and `repeataction.jar` inside your `mods/repeataction/` folder.

## Usage

| Command                  | Description                              |
|--------------------------|------------------------------------------|
| `/repeataction`          | Repeats the last action you performed    |
| `/repeataction_debug`    | Toggles debug logging on/off             |

You can bind it to a key by writing in the console, autorun.txt, or keybindings.txt:

bind R "repeataction"
