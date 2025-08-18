# CLion CMake Mux Plugin

## Overview

Default CLion will use the CMakeLists.txt in your root project directory as the top project CMakeLists.txt file. If you
want to change the project top CMakeLists.txt you can right-click on any other file in your project and select "Load
CMake Project". If you have many projects, this can become tedious to always locate each list file every time you want
to switch CMake project.

This plugin exist only to simplify this step by providing a shortcut list of user defined projects, where you can double
click on any of your shortcut projects and have CLion load that project (corresponding to "Load/Reload CMake Project").

On top of this, CLion currently will load any CMake preset defined for the project, but default they all will be
disabled. So you have to manually enable CMake presets every time you switch project CMakeLists.txt. To get around this
limitation, this plugin will also provide the configuration to enable these presets via a list of regular expressions
per project shortcut.

## Details



## Future

This plugin only exist to simplify the process of switching CMake projects in CLion. If CLion will ever support
something similar per default, this plugin will be obsolete.