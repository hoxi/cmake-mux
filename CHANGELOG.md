# Changelog

## [2026.2]

### Fixes

- An invalid preset regexp no longer prevents all other regexps from enabling presets. Invalid regexps are reported.
- Preset regexps of a previously loaded project are no longer applied when quickly switching to another project.
- More robust enabling of presets when CMake loads profiles slowly or in several steps.
- Detection of the loaded CMakeLists.txt at startup now retries with a real delay and also follows CMake reloads.

### Additions

- The same CMakeLists.txt can be pinned several times, e.g. with different preset regexps. Switching between pins of
  the loaded CMakeLists.txt only switches the enabled presets, without reloading the project.
- The active pin is shown with a yellow arrow when both its CMakeLists.txt and its presets are loaded, and with a
  gray arrow when only the CMakeLists.txt matches (e.g. loaded with CLion's "Load CMake Project").
- Loading a pin enables exactly the presets selected by its regexps, other presets are disabled.
- The name field is marked invalid when pinning or renaming with a name that already exists.
- Preset regexps can be matched against the preset name, display name or both. Existing regexps keep matching
  the name only.
- Preset regexps are validated when added or edited.
- Updated dependencies, requires CLion 2026.2 or later (JBR 25).

## [2026.1.1]

### Fixes

- CMake profiles were sometimes not loaded correctly due to timing. Fixed using event based triggers.

### Additions

- Updated dependencies

## [2026.1]

### Additions

- Updated dependencies

## [2025.3]

### Additions

- Updated dependencies

## [2025.2.2]

### Fixes

- Fixed [active project not detected at startup](https://github.com/hoxi/cmake-mux/issues/7)

### Additions

- Added [support remote development](https://github.com/hoxi/cmake-mux/issues/6)

## [2025.2.1]

### Fixes

- Small tweak to make the preset loading more robust

## [2025.2]

### Additions

- Initial release of plugin