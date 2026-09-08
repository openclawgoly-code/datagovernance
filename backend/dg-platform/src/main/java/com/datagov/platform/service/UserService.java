package com.datagov.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.platform.dto.UserView;
import com.datagov.platform.entity.PlatformEntities.User;
import com.datagov.platform.entity.PlatformEntities.UserRole;
import com.datagov.platform.mapper.UserMapper;
import com.datagov.platform.mapper.UserRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 用户管理 —— 功能 30。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, UserRoleMapper userRoleMapper,
                       PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserView> list(String keyword) {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .like(keyword != null && !keyword.isBlank(), User::getUsername, keyword)
                        .orderByDesc(User::getCreatedAt)).stream()
                .map(AuthService::toView).toList();
    }

    public UserView get(String id) {
        return AuthService.toView(require(id));
    }

    public User require(String id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "用户 " + id);
        }
        return user;
    }

    @Transactional
    public UserView create(String username, String rawPassword, String displayName,
                           String email, String phone, boolean platformAdmin, String operator) {
        requireUsernameAvailable(username, null);

        Instant now = Instant.now();
        User user = new User();
        user.setId(Ids.of("usr"));
        user.setUsername(username.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setPhone(phone);
        user.setStatus("ACTIVE");
        user.setPlatformAdmin(platformAdmin);
        user.setCreatedAt(now);
        user.setCreatedBy(operator);
        user.setUpdatedAt(now);
        user.setUpdatedBy(operator);
        user.setDeleted(false);

        userMapper.insert(user);
        log.info("用户已创建 id={} username={} platformAdmin={}",
                user.getId(), username, platformAdmin);
        return AuthService.toView(user);
    }

    @Transactional
    public UserView update(String id, String displayName, String email, String phone,
                           String operator) {
        User user = require(id);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setPhone(phone);
        user.setUpdatedAt(Instant.now());
        user.setUpdatedBy(operator);
        userMapper.updateById(user);
        return AuthService.toView(user);
    }

    @Transactional
    public void resetPassword(String id, String newPassword, String operator) {
        User user = require(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(Instant.now());
        user.setUpdatedBy(operator);
        userMapper.updateById(user);
        // 只记发生了重置,不记新口令
        log.info("用户口令已重置 id={} by={}", id, operator);
    }

    @Transactional
    public void setStatus(String id, boolean enabled, String operator) {
        User user = require(id);
        if (Boolean.TRUE.equals(user.getPlatformAdmin()) && !enabled && countActiveAdmins() <= 1) {
            // 停用最后一个平台管理员会把所有人锁在外面,且没有任何补救入口
            throw new BizException(ErrorCode.SYS_CONFLICT,
                    "不能停用最后一个平台管理员,否则将无人能管理平台");
        }
        user.setStatus(enabled ? "ACTIVE" : "DISABLED");
        user.setUpdatedAt(Instant.now());
        user.setUpdatedBy(operator);
        userMapper.updateById(user);
    }

    @Transactional
    public void delete(String id) {
        User user = require(id);
        if (Boolean.TRUE.equals(user.getPlatformAdmin()) && countActiveAdmins() <= 1) {
            throw new BizException(ErrorCode.SYS_CONFLICT, "不能删除最后一个平台管理员");
        }
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, id));
    }

    /** 在指定空间下重设某用户的角色(全量覆盖)。 */
    @Transactional
    public void assignRoles(String workspaceId, String userId, List<String> roleIds, String operator) {
        require(userId);
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getWorkspaceId, workspaceId)
                .eq(UserRole::getUserId, userId));

        for (String roleId : roleIds) {
            UserRole grant = new UserRole();
            grant.setWorkspaceId(workspaceId);
            grant.setUserId(userId);
            grant.setRoleId(roleId);
            grant.setCreatedAt(Instant.now());
            grant.setCreatedBy(operator);
            userRoleMapper.insert(grant);
        }
    }

    public List<String> listRoleIds(String workspaceId, String userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getWorkspaceId, workspaceId)
                        .eq(UserRole::getUserId, userId)).stream()
                .map(UserRole::getRoleId).toList();
    }

    private long countActiveAdmins() {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPlatformAdmin, true)
                .eq(User::getStatus, "ACTIVE"));
        return count == null ? 0 : count;
    }

    private void requireUsernameAvailable(String username, String excludeId) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username.trim())
                .ne(excludeId != null, User::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.PLT_USERNAME_DUPLICATED, username);
        }
    }
}
