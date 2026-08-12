import java.nio.file.Files
import java.nio.file.StandardOpenOption

gradle.projectsEvaluated {
    rootProject.tasks.register("supplyChainDependencies") {
        doLast {
            val coordinates = sortedSetOf<String>()
            rootProject.allprojects.forEach { project ->
                project.configurations
                    .filter { configuration ->
                        configuration.isCanBeResolved &&
                            (configuration.name.endsWith("CompileClasspath") || configuration.name.endsWith("RuntimeClasspath"))
                    }
                    .forEach { configuration ->
                        configuration.incoming.resolutionResult.allComponents.forEach { component ->
                            component.moduleVersion?.let { module ->
                                if (module.group.isNotBlank() && module.name.isNotBlank() && module.version.isNotBlank() && module.version != "unspecified") {
                                    coordinates += "${module.group}:${module.name}:${module.version}"
                                }
                            }
                        }
                    }
            }
            val output = file(gradle.startParameter.projectProperties["supplyChainOutput"]
                ?: throw GradleException("-PsupplyChainOutput is required"))
            output.parentFile.mkdirs()
            Files.writeString(
                output.toPath(),
                coordinates.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]\n") { "  \"$it\"" },
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }
}
