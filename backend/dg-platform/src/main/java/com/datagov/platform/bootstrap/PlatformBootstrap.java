package com.datagov.platform.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.platform.config.BootstrapProperties;
import com.datagov.platform.dto.UserView;
import com.datagov.platform.dto.WorkspaceView;
import com.datagov.platform.entity.PlatformEntities.User;
import com.datagov.platform.entity.PlatformEntities.Workspace;
import com.datagov.platform.mapper.UserMapper;
import com.datagov.platform.mapper.WorkspaceMapper;
import com.datagov.platform.service.UserService;
import com.datagov.platform.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

/**
 * 首次启动引导。
 *
 * <p><b>必须幂等</b>:每次启动都会跑,已存在则跳过。这不只是"避免报错" ——
 * 一个每次启动都重置管理员口令的引导程序,会让运维改过的口令在下次重启后
 * 悄悄失效。
 *
 * <p>口令策略:配置了就用配置的,没配就<b>生成随机口令并打印一次</b>。
 * 绝不内置一个固定默认口令 —— 那种口令会随代码库分发,而绝大多数部署
 * 不会去改它,等于所有安装共享同一个管理员账号。
 */
@Component
public class PlatformBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 去掉了容易混淆的 0/O/1/l/I —— 口令是要人照着念、照着抄的 */
    private static final String PASSWORD_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789@#%+=";
    private static final int GENERATED_PASSWORD_LENGTH = 16;

    private final BootstrapProperties properties;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserService userService;
    private final WorkspaceService workspaceService;

    public PlatformBootstrap(BootstrapProperties properties,
                             UserMapper userMapper,
                             WorkspaceMapper workspaceMapper,
                             UserService userService,
                             WorkspaceService workspaceService) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.workspaceMapper = workspaceMapper;
        this.userService = userService;
        this.workspaceService = workspaceService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.debug("引导已禁用(dg.bootstrap.enabled=false),跳过");
            return;
        }

        String adminId = ensureAdminUser();
        ensureDefaultWorkspace(adminId);
    }

    /** @return 管理员用户 ID(新建的或已存在的) */
    private String ensureAdminUser() {
        List<User> admins = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getPlatformAdmin, true));
        if (!admins.isEmpty()) {
            log.debug("已存在 {} 个平台管理员,跳过创建", admins.size());
            return admins.get(0).getId();
        }

        String configured = properties.getAdminPassword();
        boolean generated = configured == null || configured.isBlank();
        String password = generated ? randomPassword() : configured;

        UserView admin = userService.create(
                properties.getAdminUsername(), password,
                "平台管理员", null, null, true, "system");

        if (generated) {
            // 用 WARN 而不是 INFO:它必须在默认日志级别下可见,且要显眼。
            // 这是这个口令唯一一次出现的机会 —— 库里存的是 BCrypt 散列,不可逆。
            log.warn("""

                    ╔════════════════════════════════════════════════════════════════╗
                    ║  已创建平台管理员账号,初始口令为随机生成,仅此一次显示        ║
                    ╠════════════════════════════════════════════════════════════════╣
                    ║  用户名: {}
                    ║  口令  : {}
                    ╠════════════════════════════════════════════════════════════════╣
                    ║  请立刻登录并修改。若丢失只能重置,无法找回(库里是单向散列)。║
                    ║  要指定初始口令,请配置环境变量 DG_ADMIN_PASSWORD。            ║
                    ╚════════════════════════════════════════════════════════════════╝
                    """, properties.getAdminUsername(), password);
        } else {
            log.info("已按配置创建平台管理员 username={}", properties.getAdminUsername());
        }
        return admin.id();
    }

    private void ensureDefaultWorkspace(String adminId) {
        Long count = workspaceMapper.selectCount(new LambdaQueryWrapper<Workspace>());
        if (count != null && count > 0) {
            log.debug("已存在 {} 个空间,跳过创建默认空间", count);
            return;
        }

        WorkspaceView workspace = workspaceService.create(
                properties.getDefaultWorkspaceCode(),
                properties.getDefaultWorkspaceName(),
                "首次启动自动创建", "system");
        workspaceService.addMembers(workspace.id(), List.of(adminId), "system");

        log.info("已创建默认空间 code={} name={}", workspace.code(), workspace.name());
    }

    private static String randomPassword() {
        StringBuilder builder = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
            builder.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }
}
