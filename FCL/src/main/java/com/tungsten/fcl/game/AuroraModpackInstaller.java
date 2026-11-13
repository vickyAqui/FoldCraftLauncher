package com.tungsten.fcl.game;

import com.tungsten.fcl.setting.Profile;
import com.tungsten.fclcore.download.DefaultDependencyManager;
import com.tungsten.fclcore.task.FileDownloadTask;
import com.tungsten.fclcore.task.GetTask;
import com.tungsten.fclcore.task.Schedulers;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.gson.JsonUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs the official Aurora Studios modpack on top of a Forge 1.20.1 instance.
 * The mod list is read from the same modpack.json used by the desktop launcher,
 * so the mobile and desktop clients always receive the same mods.
 */
public final class AuroraModpackInstaller {

    public static final String VERSION_NAME = "Aurora Studios";
    public static final String GAME_VERSION = "1.20.1";
    public static final String FORGE_VERSION = "47.4.10";
    public static final String MANIFEST_URL = "https://github.com/vickyAqui/aurora-launcher/releases/download/modpack/modpack.json";
    public static final String SERVER_ADDRESS = "79.182.128.137:25565";

    private AuroraModpackInstaller() {
    }

    public static Task<Void> createInstallTask(Profile profile, String name) {
        return new GetTask(newUrl(MANIFEST_URL)).thenComposeAsync(json -> {
            Manifest manifest = JsonUtils.fromNonNullJson(json, Manifest.class);
            return createInstallTask(profile, name, manifest);
        });
    }

    private static Task<Void> createInstallTask(Profile profile, String name, Manifest manifest) {
        return new InstallTask(profile, name, manifest).thenRunAsync(Schedulers.androidUIThread(), () -> {
            profile.setSelectedVersion(name);
            profile.getVersionSetting(name).setServerIp(SERVER_ADDRESS);
        });
    }

    private static URL newUrl(String url) {
        try {
            return new URL(url);
        } catch (IOException e) {
            throw new IllegalArgumentException(url, e);
        }
    }

    private static final class InstallTask extends Task<Void> {
        private final DefaultDependencyManager dependency;
        private final FCLGameRepository repository;
        private final String name;
        private final List<FileEntry> files;
        private final List<Task<?>> dependencies = new ArrayList<>();
        private final List<Task<?>> dependents = new ArrayList<>(1);

        private InstallTask(Profile profile, String name, Manifest manifest) {
            this.dependency = profile.getDependency();
            this.repository = profile.getRepository();
            this.name = name;
            this.files = manifest.files == null ? new ArrayList<>() : manifest.files;

            if (repository.hasVersion(name) && !repository.getModpackConfiguration(name).exists())
                throw new IllegalArgumentException("Version " + name + " already exists.");

            dependents.add(dependency.gameBuilder()
                    .name(name)
                    .gameVersion(GAME_VERSION)
                    .version("forge", FORGE_VERSION)
                    .buildAsync());

            onDone().register(event -> {
                if (event.isFailed())
                    repository.removeVersionFromDisk(name);
            });
        }

        @Override
        public List<Task<?>> getDependents() {
            return dependents;
        }

        @Override
        public List<Task<?>> getDependencies() {
            return dependencies;
        }

        @Override
        public void execute() throws Exception {
            Path root = repository.getRunDirectory(name).toPath().toAbsolutePath().normalize();
            int total = 0;

            for (FileEntry entry : files) {
                if (entry == null || entry.name == null || entry.url == null)
                    continue;
                if (entry.type != null && !"MOD".equalsIgnoreCase(entry.type))
                    continue;

                String relative = (entry.path == null ? "" : entry.path) + entry.name;
                Path destination = root.resolve(relative).normalize();
                if (!destination.startsWith(root))
                    throw new IOException("Unsafe modpack path: " + relative);

                FileDownloadTask.IntegrityCheck check = entry.sha1 == null || entry.sha1.isEmpty()
                        ? null
                        : new FileDownloadTask.IntegrityCheck("SHA-1", entry.sha1);
                dependencies.add(new FileDownloadTask(new URL(entry.url), destination.toFile(), check)
                        .withCounter("fcl.modpack.download"));
                total++;
            }

            getProperties().put("total", total);
            notifyPropertiesChanged();
        }
    }

    private static final class Manifest {
        private List<FileEntry> files;
    }

    private static final class FileEntry {
        private String name;
        private String path;
        private String url;
        private String sha1;
        private String type;
        private long size;
    }
}
