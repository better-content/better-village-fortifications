# Changelog

## Unreleased

### Changed

- Replaced flat two-block wall styles with weighted foundation, body, support, cap, parapet, and attachment rules.
- Added automatic regional style selection and optional modded palette entries with vanilla fallbacks.
- Made large-village rampart materials data-driven while preserving existing wall geometry and traversal behavior.
- Standardized the project as **Village Walls** with mod ID `village_walls`, artifact `village-walls`, and package `com.bettercontent.villagewalls`.
- Adopted Java 17 and Forge 1.20.1-47.4.13 as the build baseline without changing the project version.
- This is a clean break; legacy worlds, configurations, and integrations are not migrated.
