# CS16MC

A 26.2 fabric mod, which reimplements Counter-Strike 1.6 weapons inside of Minecraft 26.2
It integrates ALL, yes ALL the cs16 weapons into minecraft, with remade functionality.


# This is a little project of mine that i started because of the rust rewrites, and i though, why shouldnt i write a mod for minecraft with fabric loom that reimplements cs16 weapons into minecraft??



# features and current mod state:

all cs16 weapons (including grenades!!!)
weapons can damage mobs and blocks!
blocks such as weed or grass (most blocks that arent full) are penetrable.
mobs are alerted by gunshots (radius depends on surrounding blocks and wheather the weapon has a silencer on.)
grenade physics are basically identical to cs16's (really cool!)
i tried to optimize this as much as possible and it is getting around stable 140fps on my old laptop. (it will be really laggy at like the first 5 seconds of the world generating.)
it supports sodium and other optimization mods, (shaders arent tested yet.)

# FAQ

# Will this get updated to newer versions?
probably, but if it reaches a version that uses a new java version, or just overall changes most things, ill most likely still update it but dont take my word for it.

# Will this get bug fixes?
maybe, i dont plan to, only if its gamebreaking.

# Was ai used?
partially, i mostly used it to fix bugs in my code, im still learning java.

# How do i build this from source?
this includes a gradle wrapper and gradlew.bat
so cd into the main project folder, and type gradlew.bat build inside a command prompt, it will output a jar file inside of build/libs folder.

requirements: Java 25+
