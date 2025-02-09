package moe.caa.multilogin.dataupgrade;

import moe.caa.multilogin.dataupgrade.newc.NewConfig;
import moe.caa.multilogin.dataupgrade.newc.NewSQLHandler;
import moe.caa.multilogin.dataupgrade.newc.yggdrasil.NewYggdrasilConfig;
import moe.caa.multilogin.dataupgrade.oldc.*;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据升级程序未完成
 */
public class Main {
    public static List<OldUserData> oldUserDataList;
    private static OldConfig oldConfig;

    public static void main(String[] args) throws InterruptedException {
        if (new File("output").exists()) {
            System.err.println("Folder output already exists, has it been upgraded before?");
            return;
        }
        long timeMillis = System.currentTimeMillis();
        readOldData();
        if (oldUserDataList == null || oldConfig == null) {
            return;
        }
        System.out.println("Checking import...");
        Map<String, Set<OldUserData>> checkResultMap = checkImport();
        if (checkResultMap.size() != 0) {
            System.out.println();
            System.err.println("================================================================");
            for (Map.Entry<String, Set<OldUserData>> entry : checkResultMap.entrySet()) {
                System.err.println(" Yggdrasil Service with path " + entry.getKey() + " is not found, this will affect the " + entry.getValue().size() + " bar data!");
            }
            System.err.println(" The affected data will not be upgraded!!!");
            System.err.println(" Will continue in 15 seconds. Abort immediately if necessary.");
            System.err.println("================================================================");
            Thread.sleep(15000);
        }

        System.out.println("================================================================");
        System.out.println(" Allocated Yggdrasil ID:");
        for (int i = 0; i < oldConfig.getServices().size(); i++) {
            System.out.printf("      ID: %d is allocated to the Yggdrasil Service where path is %s and name is %s.%n", i
                    , oldConfig.getServices().get(i).getPath(), oldConfig.getServices().get(i).getName());
        }
        System.out.println();
        System.out.println(" Unhappy with the allocation? You can immediately close the program, change the order of the service children in the configuration file, and run the program again.");
        System.out.println(" Writing will begin in 15 seconds.");
        System.out.println("================================================================");
        Thread.sleep(15000);

        try {
            convertAndWrite();
        } catch (Exception e) {
            System.err.println("An exception occurs when processing upgrade data.");
            e.printStackTrace();
            return;
        }

        System.out.printf("\nDone. Total time: %.2f seconds.", ((System.currentTimeMillis() - timeMillis) + 1.0) / 1000);
    }

    public static void convertAndWrite() throws IOException {
        File outputFile = new File("output");
        if (!outputFile.exists()) Files.createDirectories(outputFile.toPath());
        System.out.println("Converting configuration...");

        NewConfig config = new NewConfig(oldConfig);
        CommentedConfigurationNode configYaml = config.toYaml();
        YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK).file(new File(outputFile, "config.yml")).build().save(configYaml);

        File outputService = new File(outputFile, "services");
        if (!outputService.exists()) Files.createDirectories(outputService.toPath());
        try (Stream<Path> list = Files.list(outputService.toPath())) {
            for (Path path : list.toArray(Path[]::new)) {
                Files.delete(path);
            }
        }
        for (int i = 0; i < oldConfig.getServices().size(); i++) {
            File service = new File(outputService, oldConfig.getServices().get(i).getPath() + ".yml");
            NewYggdrasilConfig yggdrasilConfig = new NewYggdrasilConfig(i, oldConfig, oldConfig.getServices().get(i));
            YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK).file(service).build().save(yggdrasilConfig.toYaml());
        }

        NewSQLHandler newSQLHandler;
        try {
            System.out.println("Loading the new " + config.getS_backend().name().toLowerCase() + " database driver...");
            newSQLHandler = new NewSQLHandler(outputFile, config);
        } catch (Throwable e) {
            System.err.println("Cannot process new database, please check.");
            e.printStackTrace();
            return;
        }

        System.out.println("Converting data...");
        // 处理重名数据
        Set<String> cache = new HashSet<>();
        for (OldUserData data : oldUserDataList) {
            data.setCurrentName(data.getCurrentName().toLowerCase());
            if (!cache.add(data.getCurrentName())) {
                System.out.println("Processing duplicate name: " + data.getCurrentName());
                data.setCurrentName(null);
            }
        }


        List<String> sSt = new ArrayList<>();
        for (OldYggdrasilConfig service : oldConfig.getServices()) {
            sSt.add(service.getPath());
        }

        int failed = 0;
        for (OldUserData data : oldUserDataList) {
            int i = sSt.indexOf(data.getYggdrasilService());
            if (i != -1) {
                try {
                    newSQLHandler.insertNewUserData(i, data);
                } catch (SQLException e) {
                    e.printStackTrace();
                    failed++;
                }
            } else {
                failed++;
            }
        }

        newSQLHandler.close();
        System.out.println("\n" + failed + " failure.");
    }

    /**
     * 检查数据完整
     */
    public static Map<String, Set<OldUserData>> checkImport() {
        Set<String> set = oldConfig.getServices().stream().map(OldYggdrasilConfig::getPath).collect(Collectors.toSet());

        Map<String, Set<OldUserData>> lossPath = new HashMap<>();
        for (OldUserData data : oldUserDataList) {
            if (set.contains(data.getYggdrasilService())) continue;
            Set<OldUserData> lp = lossPath.getOrDefault(data.getYggdrasilService(), new HashSet<>());
            lp.add(data);
            lossPath.put(data.getYggdrasilService(), lp);
        }
        return lossPath;
    }

    // 读老数据
    public static void readOldData() {
        File configFile = new File("config.yml");
        File advancedConfigFile = new File("advanced_setting.properties");

        if (!configFile.exists()) {
            System.err.println("The config.yml file could not be found.");
            System.err.println("You need to move this program to the same directory as config.yml(old), Try again.");
            return;
        }

        // 解析 config.yml 文件
        CommentedConfigurationNode configurationNodeConfigYml;
        try {
            configurationNodeConfigYml = YamlConfigurationLoader.builder().file(configFile).build().load();
        } catch (ConfigurateException e) {
            System.err.println("An exception occurred while reading the config.yml file, maybe it's damaged.");
            e.printStackTrace();
            return;
        }

        // 老文件是必须有这个节点的，没有则代表是坏的。
        if (!configurationNodeConfigYml.hasChild("services")) {
            System.err.println("No services configuration was found.");
            System.err.println("Has it been successfully upgraded before?");
            return;
        }

        // 读取他的全部配置
        final OldConfig oldConfig;
        try {
            oldConfig = new OldConfig(configurationNodeConfigYml);
        } catch (Throwable e) {
            System.err.println("An exception occurred while parsing the config.yml file, maybe it's damaged.");
            e.printStackTrace();
            return;
        }

        // 读取高级设置文件
        final OldAdvancedConfig oldAdvancedConfig;
        if (advancedConfigFile.exists()) {
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(advancedConfigFile));
                oldAdvancedConfig = new OldAdvancedConfig(properties);
            } catch (Exception e) {
                System.err.println("An exception occurred while parsing the advanced_setting.properties file, maybe it's damaged.");
                e.printStackTrace();
                return;
            }
        } else {
            oldAdvancedConfig = new OldAdvancedConfig(new Properties());
        }

        // 读数据
        OldSQLHandler oldSQLHandler;
        try {
            System.out.println("Loading the old " + oldConfig.getS_backend().name().toLowerCase() + " database driver...");
            oldSQLHandler = new OldSQLHandler(oldConfig, oldAdvancedConfig);
        } catch (Throwable e) {
            System.err.println("Cannot process old database, please check.");
            e.printStackTrace();
            return;
        }

        List<OldUserData> oldUserData;
        try {
            System.out.println("Importing data...");
            oldUserData = oldSQLHandler.readAll();
        } catch (SQLException e) {
            System.err.println("Cannot read old data.");
            e.printStackTrace();
            return;
        }
        oldSQLHandler.close();

        System.out.println(oldUserData.size() + " user data have been imported.");
        System.out.println(oldConfig.getServices().size() + " yggdrasil service have been imported.");

        Main.oldConfig = oldConfig;
        Main.oldUserDataList = oldUserData;
    }
}
