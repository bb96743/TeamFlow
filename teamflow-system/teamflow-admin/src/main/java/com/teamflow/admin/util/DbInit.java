package com.teamflow.admin.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 本地开发库初始化 / 救援工具。
 *
 * <p><b>G-4 之后日常 DDL 不再走本工具</b>：数据库迁移已由 Flyway 纳管（2026-10-25），
 * 应用启动时自动执行 {@code classpath:db/migration} 下的 {@code V*.sql}，
 * 脚本唯一数据源是 {@code teamflow-system/sql/}。DbInit 只保留两个用途：</p>
 * <ol>
 *   <li><b>全新建库</b>：在完全空的环境一次性建库并导入全部脚本（无版本表）；</li>
 *   <li><b>救援</b>：数据库损坏时推倒重来（{@code fix} 模式，会被 Drop 数据库）。</li>
 * </ol>
 *
 * <p><b>{@code fix} 安全开关</b>（{@code fix} 会 {@code DROP DATABASE}，删除全部数据）：</p>
 * <ul>
 *   <li>必须显式确认：命令行追加 {@code --force}，或设置环境变量 {@code TEAMFLOW_DB_FIX_CONFIRM=true}；</li>
 *   <li>禁止生产 profile：{@code SPRING_PROFILES_ACTIVE} 或 {@code TEAMFLOW_PROFILE} 含 {@code prod} 时一律拒绝；</li>
 *   <li>未确认时只打印风险说明与确认方式，退出码 2，不执行任何删除。</li>
 * </ul>
 *
 * <p><b>注意</b>：本工具按文件名顺序执行 {@code V*.sql}，<b>不创建 {@code flyway_schema_history}</b>。
 * 因此用它初始化出的库在首次被应用启动（Flyway）接管时，会按「既有库」路径自动 baseline 到 V2
 * 并补跑 V3/V4——这两个脚本是 {@code CREATE TABLE IF NOT EXISTS} / {@code INSERT IGNORE}，
 * 补跑安全。参见 {@code docs/项目技术栈与启动指南.md} §4.3。</p>
 *
 * <p>运行方式（工作目录须为 {@code teamflow-system/teamflow-admin}，默认脚本目录 {@code ../sql}；
 * 也可用 {@code TEAMFLOW_SQL_DIR} 显式指定）：</p>
 * <pre>
 * # 仅建库 + 导入脚本（安全，不删库）
 * java -cp ... com.teamflow.admin.util.DbInit
 *
 * # 重建（破坏性，需确认；同时受 prod 保护）
 * java -cp ... com.teamflow.admin.util.DbInit fix --force
 * </pre>
 *
 * <p>连接信息与 {@code application-dev.yml} 使用同一组环境变量：
 * {@code DB_HOST} / {@code DB_PORT} / {@code DB_NAME} / {@code DB_USERNAME} / {@code DB_PASSWORD} / {@code DB_TIMEZONE}。</p>
 */
public class DbInit {

    /** fix 模式的环境变量确认开关。 */
    private static final String FIX_CONFIRM_ENV = "TEAMFLOW_DB_FIX_CONFIRM";
    /** fix 模式的命令行确认参数。 */
    private static final String FORCE_ARG = "--force";
    /** 生产 profile 关键字：命中即拒绝 fix。 */
    private static final String PROD_MARKER = "prod";

    public static void main(String[] args) throws Exception {
        String host = env("DB_HOST", "localhost");
        String port = env("DB_PORT", "3306");
        String db = env("DB_NAME", "teamflow");
        String user = env("DB_USERNAME", "root");
        String pass = env("DB_PASSWORD", "root");
        if (!db.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("DB_NAME 只能包含字母、数字和下划线");
        String zone = env("DB_TIMEZONE", "+08:00");
        String params = "useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true";
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", pass);
        properties.setProperty("connectionTimeZone", zone);
        properties.setProperty("forceConnectionTimeZoneToSession", "true");
        boolean fix = Arrays.asList(args).contains("fix");
        if (fix) {
            requireFixAllowed(Arrays.asList(args).contains(FORCE_ARG));
        }

        try (Connection c = DriverManager.getConnection(String.format("jdbc:mysql://%s:%s/?%s", host, port, params), properties);
             Statement s = c.createStatement()) {
            if (fix) {
                System.out.println("[危险操作已确认] 删除并重建数据库: " + db);
                s.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
            s.execute("CREATE DATABASE IF NOT EXISTS `" + db + "` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci");
        }

        Path sqlDir = Path.of(env("TEAMFLOW_SQL_DIR", "../sql"));
        if (!Files.isDirectory(sqlDir)) {
            throw new IllegalStateException("找不到迁移脚本目录：" + sqlDir.toAbsolutePath()
                    + "。请在 teamflow-system 目录下运行，或设置环境变量 TEAMFLOW_SQL_DIR 指向 sql 目录");
        }
        List<Path> files;
        try (var stream = Files.list(sqlDir)) {
            files = stream.filter(f -> f.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(DbInit::version))
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("迁移脚本目录为空：" + sqlDir.toAbsolutePath());
        }

        try (Connection c = DriverManager.getConnection(String.format("jdbc:mysql://%s:%s/%s?%s", host, port, db, params), properties);
             Statement s = c.createStatement()) {
            for (Path f : files) {
                System.out.println("导入: " + f.getFileName());
                s.execute(Files.readString(f));
            }
        }
        System.out.println("数据库初始化完成（未创建 flyway_schema_history，首次启动应用时 Flyway 会自动 baseline 到 V2 并补跑 V3+）");
    }

    /** fix 模式的强制确认与生产环境保护。 */
    private static void requireFixAllowed(boolean forcedByArg) {
        String activeProfiles = (env("SPRING_PROFILES_ACTIVE", "") + "," + env("TEAMFLOW_PROFILE", ""))
                .toLowerCase(Locale.ROOT);
        if (activeProfiles.contains(PROD_MARKER)) {
            throw new IllegalStateException("拒绝执行：SPRING_PROFILES_ACTIVE / TEAMFLOW_PROFILE 包含 prod。"
                    + "DbInit fix 会 DROP DATABASE，禁止在生产或类生产环境运行");
        }
        boolean confirmedByEnv = "true".equalsIgnoreCase(env(FIX_CONFIRM_ENV, "false"));
        if (!forcedByArg && !confirmedByEnv) {
            System.err.println("""
                    [已阻止] DbInit fix 会 DROP DATABASE 并重建数据库，属于破坏性操作，未执行任何删除。
                    如需继续，二选一显式确认：
                      1) 命令行追加 --force          例：DbInit fix --force
                      2) 设置环境变量 %s=true
                    另外：SPRING_PROFILES_ACTIVE / TEAMFLOW_PROFILE 含 prod 时一律拒绝。
                    若只想「建库 + 导入脚本」，不要传 fix（安全，不会删库）。
                    """.formatted(FIX_CONFIRM_ENV));
            System.exit(2);
        }
    }

    private static int version(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
