# CLion CMake Mux Plugin

![Light theme logo](docs/logo.png#gh-light-mode-only)
![Dark theme logo](docs/logo_dark.png#gh-dark-mode-only)

## Overview

This plugin extends CLion’s CMake integration with:

- a project tool window context action, “Pin to CMake Mux”, and
- a popup action, “Select CMake Mux Project...”.

Together, they make switching between multiple CMake projects fast and convenient. The plugin can also auto-enable
selected CMake presets, since CLion (currently) loads imported presets disabled by default.

## Background

By default, CLion uses the `CMakeLists.txt` in your project root as the top-level CMake file. To switch, you can
right‑click another `CMakeLists.txt` and choose “Load CMake Project.” If you switch frequently, repeating this step
becomes tedious.

This plugin adds a shortlist of user-defined projects. Double‑click any item to load it (equivalent to “Load/Reload
CMake Project”).

Additionally, while CLion imports any CMake presets it finds, they start disabled. The plugin lets you define regular
expressions per project shortcut to automatically enable matching presets after switching.

## Details

When you right‑click any `CMakeLists.txt`, the plugin provides a “Pin to CMake Mux” in the context menu.

![Pin to CMake Mux](docs/pin_to_mux.png)

Adding a `CMakeLIsts.txt` to the mux requires a nickname:

![pin_to_mux_window.png](docs/pin_to_mux_window.png)

The plugin also provides a panel where you can manage your pinned CMake projects.

![mux_tool_win.png](docs/mux_tool_win.png)

The panel lists pinned projects and highlights the active one with a yellow arrow. Double‑click any project to load it
using CLion’s “Load CMake Project.” The “Enable CMake presets for <project>” panel lets you specify any number of
regular expressions; presets whose names match are enabled automatically.

![main_mux_win.png](docs/main_mux_win.png)

The action “Select CMake Mux Project...” can be bound to a custom keyboard shortcut. Invoking it shows a popup with the
first nine projects. Press a digit to load the corresponding project, or use the arrow keys and Enter.

![select_mux_proj.png](docs/select_mux_proj.png)

> [!NOTE]
> The plugin has no additional settings. It simply extends the CLion UI to streamline multi‑project CMake workflows.

## Future

This plugin exists solely to simplify switching CMake projects in CLion. If CLion adds similar functionality natively,
this plugin will become unnecessary.