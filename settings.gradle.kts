pluginManagement {
    repositories {
        jcenter()
        gradlePluginPortal()
        maven {
            name = "forge"
            setUrl("https://maven.minecraftforge.net/")
        }
    }
    resolutionStrategy {
        eachPlugin {
            if(requested.id.namespace == "net.minecraftforge.gradle") {
                useModule("com.anatawa12.forge:ForgeGradle:${requested.version}")
            }
        }
    }
}

