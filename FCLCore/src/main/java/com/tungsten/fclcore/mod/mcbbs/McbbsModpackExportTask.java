/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fclcore.mod.mcbbs;

import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.FABRIC;
import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.FORGE;
import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.LITELOADER;
import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.MINECRAFT;
import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.NEO_FORGE;
import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.OPTIFINE;
import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.QUILT;

import com.tungsten.fclcore.download.LibraryAnalyzer;
import com.tungsten.fclcore.game.DefaultGameRepository;
import com.tungsten.fclcore.game.Library;
import com.tungsten.fclcore.mod.ModAdviser;
import com.tungsten.fclcore.mod.Modpack;
import com.tungsten.fclcore.mod.ModpackExportInfo;
import com.tungsten.fclcore.mod.curse.CurseManifest;
import com.tungsten.fclcore.mod.curse.CurseManifestMinecraft;
import com.tungsten.fclcore.mod.curse.CurseManifestModLoader;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.DigestUtils;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fclcore.util.StringUtils;
import com.tungsten.fclcore.util.gson.JsonUtils;
import com.tungsten.fclcore.util.io.Zipper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class McbbsModpackExportTask extends Task<Void> {
    private final DefaultGameRepository repository;
    private final String version;
    private final ModpackExportInfo info;
    private final File modpackFile;

    public McbbsModpackExportTask(DefaultGameRepository repository, String version, ModpackExportInfo info, File modpackFile) {
        this.repository = repository;
        this.version = version;
        this.info = info.validate();
        this.modpackFile = modpackFile;

        onDone().register(event -> {
            if (event.isFailed()) modpackFile.delete();
        });
    }

    @Override
    public void execute() throws Exception {
        ArrayList<String> blackList = new ArrayList<>(ModAdviser.MODPACK_BLACK_LIST);
        blackList.add(version + ".jar");
        blackList.add(version + ".json");
        Logging.LOG.info("Compressing game files without some files in blacklist, including files or directories: usernamecache.json, asm, logs, backups, versions, assets, usercache.json, libraries, crash-reports, launcher_profiles.json, NVIDIA, TCNodeTracker");
        try (Zipper zip = new Zipper(modpackFile.toPath())) {
            Path runDirectory = repository.getRunDirectory(version).toPath();
            List<McbbsModpackManifest.File> files = new ArrayList<>();
            zip.putDirectory(runDirectory, "overrides", path -> {
                if (Modpack.acceptFile(path, blackList, info.getWhitelist())) {
                    Path file = runDirectory.resolve(path);
                    if (Files.isRegularFile(file)) {
                        String relativePath = runDirectory.relativize(file).normalize().toString().replace(File.separatorChar, '/');
                        files.add(new McbbsModpackManifest.AddonFile(true, relativePath, DigestUtils.digestToString("SHA-1", file)));
                    }
                    return true;
                } else {
                    return false;
                }
            });

            String gameVersion = repository.getGameVersion(version)
                    .orElseThrow(() -> new IOException("Cannot parse the version of " + version));
            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(repository.getResolvedPreservingPatchesVersion(version), gameVersion);

            // Mcbbs manifest
            List<McbbsModpackManifest.Addon> addons = new ArrayList<>();
            addons.add(new McbbsModpackManifest.Addon(MINECRAFT.getPatchId(), gameVersion));
            analyzer.getVersion(FORGE).ifPresent(forgeVersion ->
                    addons.add(new McbbsModpackManifest.Addon(FORGE.getPatchId(), forgeVersion)));
            analyzer.getVersion(NEO_FORGE).ifPresent(neoForgeVersion ->
                    addons.add(new McbbsModpackManifest.Addon(NEO_FORGE.getPatchId(), neoForgeVersion)));
            analyzer.getVersion(LITELOADER).ifPresent(liteLoaderVersion ->
                    addons.add(new McbbsModpackManifest.Addon(LITELOADER.getPatchId(), liteLoaderVersion)));
            analyzer.getVersion(OPTIFINE).ifPresent(optifineVersion ->
                    addons.add(new McbbsModpackManifest.Addon(OPTIFINE.getPatchId(), optifineVersion)));
            analyzer.getVersion(FABRIC).ifPresent(fabricVersion ->
                    addons.add(new McbbsModpackManifest.Addon(FABRIC.getPatchId(), fabricVersion)));
            analyzer.getVersion(QUILT).ifPresent(quiltVersion ->
                    addons.add(new McbbsModpackManifest.Addon(QUILT.getPatchId(), quiltVersion)));

            List<Library> libraries = new ArrayList<>();
            // TODO libraries

            List<McbbsModpackManifest.Origin> origins = new ArrayList<>();
            // TODO origins

            McbbsModpackManifest.Settings settings = new McbbsModpackManifest.Settings();
            McbbsModpackManifest.LaunchInfo launchInfo = new McbbsModpackManifest.LaunchInfo(info.getMinMemory(), info.getSupportedJavaVersions(), StringUtils.tokenize(info.getLaunchArguments()), StringUtils.tokenize(info.getJavaArguments()));

            McbbsModpackManifest mcbbsManifest = new McbbsModpackManifest(McbbsModpackManifest.MANIFEST_TYPE, 2, info.getName(), info.getVersion(), info.getAuthor(), info.getDescription(), info.getFileApi() == null ? null : StringUtils.removeSuffix(info.getFileApi(), "/"), info.getUrl(), info.isForceUpdate(), origins, addons, libraries, files, settings, launchInfo);
            zip.putTextFile(JsonUtils.GSON.toJson(mcbbsManifest), "mcbbs.packmeta");

            // CurseForge manifest
            List<CurseManifestModLoader> modLoaders = new ArrayList<>();
            analyzer.getVersion(FORGE).ifPresent(forgeVersion -> modLoaders.add(new CurseManifestModLoader("forge-" + forgeVersion, true)));
            analyzer.getVersion(NEO_FORGE).ifPresent(forgeVersion -> modLoaders.add(new CurseManifestModLoader("neoforge-" + forgeVersion, true)));
            analyzer.getVersion(FABRIC).ifPresent(fabricVersion -> modLoaders.add(new CurseManifestModLoader("fabric-" + fabricVersion, true)));
            // OptiFine and LiteLoader are not supported by CurseForge modpack.
            CurseManifest curseManifest = new CurseManifest(CurseManifest.MINECRAFT_MODPACK, 1, info.getName(), info.getVersion(), info.getAuthor(), "overrides", new CurseManifestMinecraft(gameVersion, modLoaders), Collections.emptyList());
            zip.putTextFile(JsonUtils.GSON.toJson(curseManifest), "manifest.json");
        }
    }

    public static final ModpackExportInfo.Options OPTION = new ModpackExportInfo.Options()
            .requireFileApi(true)
            .requireUrl()
            .requireForceUpdate()
            .requireMinMemory()
            .requireAuthlibInjectorServer()
            .requireJavaArguments()
            .requireLaunchArguments()
            .requireOrigins();

}
