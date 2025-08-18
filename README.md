# CLion CMake Mux Plugin

## Overview

This plugin extends the CLion CMake integration by adding a new project window popup entry `"Pin to CMake Mux"`,
and a new popup action `"Select CMake Mux Project..."`. With these two features, you can easily switch between
multiple CMake projects in CLion. Integrated with this is also the ability to automatically enable selected CMake
presets, since CLion per default will load presets in a disabled state.

## Background

Default CLion will use the `CMakeLists.txt` in your root project directory as the top project `CMakeLists.txt` file. If
you want to change the project top CMake configuration, you can right-click on any other file in your project and select
`"Load CMake Project"`. If you have many projects, with frequent project switches, this manual popup-selection can
become tedious.

This plugin will simplify this step by providing a shortcut list of user defined projects, where you can double
click on any of your shortcut projects and have CLion load that project (corresponding to`"Load/Reload CMake Project"`).

On top of this, CLion currently will load any CMake preset defined for the project, but default they all will be
disabled. So you have to manually enable CMake presets every time you switch project `CMakeLists.txt`. To get around
this limitation, this plugin will also provide the configuration to enable these presets via a list of regular
expressions per project shortcut.

## Details

This plugin adds a new project window popup entry `"Pin to CMake Mux"` when you right-click on any `CMakeLists.txt`
file.

![Pin to CMake Mux](docs/pin_to_mux.png)

Adding a CMakeLists.txt to the mux will require a nickname:

![pin_to_mux_window.png](docs/pin_to_mux_window.png)

A new panel is added by the plugin, where you can manage your pinned CMake projects.

![mux_tool_win.png](docs/mux_tool_win.png)

Opening this panel will present the list of pinned CMake projects, with the currently active CMake project highlighted
with a yellow arrow. Whn you double-click on any of the projects, CLion will load that project (via the CLion standard `"Load
CMake Project"`). The optional `"Enable CMake presets for <project>"` can be used to define aany number of regular
expressions that will be compared towards the loaded CMake presets for the project, and any preset matching the
regular expression will automatically be enabled.

![main_mux_win.png](docs/main_mux_win.png)

The plugin also defines a popup action `"Select CMake Mux Project..."`, for which you can create a keyboard shortcut.
Invoking this will display a popup for the first nine defined projects. You can load any project on the list by simply
pressing the corresponding key, or use keyboard arrows + `<Enter>` to load.

![select_mux_proj.png](docs/select_mux_proj.png)

Note that this plugin does not have any other settings or configuration options, it simply extends the current CLion UI
to simplify multi-projects CMake workflows.

## Future

This plugin only exist to simplify the process of switching CMake projects in CLion. If CLion will ever support
something similar per default, this plugin will be obsolete.