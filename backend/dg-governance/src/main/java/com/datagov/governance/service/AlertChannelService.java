package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.GovernanceEntities.AlertChannel;
import com.datagov.governance.mapper.AlertChannelMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 告警渠道(序号 33)。
 *
 * <p><b>菜单在「基础配置」下,归属却是 Governance</b>(架构风险 R2)。理由与
 * 执行器、制品相同:渠道有<b>连通性状态</b>。一个发不出去的渠道会让挂在它上面
 * 的所有告警静默失效 —— 界面上告警都好好地记着,而没有一个人收到过。
 * 这是治理问题,不是配置项。
 *
 * <p>所以「连通性测试」(需求明确要求)不是一个锦上添花的按钮,而是这个功能
 * 存在的主要理由之一:配完之后必须能当场验证它真的能发出去。
 */
@Service
public class AlertChannelService {

    private static final Logger log = LoggerFactory.getLogger(AlertChannelService.class);

    private static final List<String> TYPES = List.of("EMAIL", "WEBHOOK");
    /** 渠道测试的超时。比业务请求短得多:一个发不出去的渠道该快点告诉用户 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private final AlertChannelMapper channelMapper;
    private final ObjectMapper objectMapper;
    /** 没配 SMTP 时为 null —— 那时邮件渠道能建但测试会明确失败,而不是静默不发 */
    private final JavaMailSender mailSender;
    private final HttpClient httpClient;

    public AlertChannelService(AlertChannelMapper channelMapper, ObjectMapper objectMapper,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               JavaMailSender mailSender) {
        this.channelMapper = channelMapper;
        this.objectMapper = objectMapper;
        this.mailSender = mailSender;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TEST_TIMEOUT).build();
    }

    /** 渠道视图。不含 configJson 的原文 —— Webhook 的请求头里可能有令牌。 */
    public record ChannelView(
            String id,
            String name,
            String type,
            String status,
            /** 脱敏后的目标描述:邮箱列表或 URL 的主机部分 */
            String target,
            Instant lastTestedAt,
            Boolean lastTestSucceeded,
            String lastTestMessage,
            Instant createdAt,
            String createdBy
    ) {
    }

    public record TestResult(boolean succeeded, String message, Instant testedAt) {
    }

    public List<ChannelView> list() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return channelMapper.selectList(new LambdaQueryWrapper<AlertChannel>()
                        .eq(AlertChannel::getWorkspaceId, workspaceId)
                        .orderByDesc(AlertChannel::getCreatedAt)).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ChannelView create(String name, String type, Map<String, Object> config) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();

        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "渠道名称不能为空");
        }
        String effectiveType = type == null ? "" : type.trim().toUpperCase();
        if (!TYPES.contains(effectiveType)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "不支持的渠道类型: " + type, "可选:" + String.join(" / ", TYPES));
        }
        validateConfig(effectiveType, config);
        requireNameAvailable(workspaceId, name, null);

        Instant now = Instant.now();
        AlertChannel channel = new AlertChannel();
        channel.setId(Ids.of("chn"));
        channel.setWorkspaceId(workspaceId);
        channel.setName(name.trim());
        channel.setType(effectiveType);
        channel.setConfigJson(writeJson(config));
        // 新建的渠道是 ACTIVE 但"从未测试过" —— 界面要把这两件事分开显示,
        // 否则用户会以为它已经验证可用了
        channel.setStatus("ACTIVE");
        channel.setCreatedAt(now);
        channel.setCreatedBy(operator);
        channel.setUpdatedAt(now);
        channel.setUpdatedBy(operator);
        channel.setDeleted(false);
        channelMapper.insert(channel);

        log.info("告警渠道已创建 workspace={} id={} type={}", workspaceId, channel.getId(), effectiveType);
        return toView(channel);
    }

    @Transactional
    public ChannelView update(String id, String name, Map<String, Object> config) {
        AlertChannel channel = requireChannel(id);
        requireNameAvailable(channel.getWorkspaceId(), name, id);
        validateConfig(channel.getType(), config);

        channel.setName(name.trim());
        channel.setConfigJson(writeJson(config));
        // 改过配置之后,上一次的测试结果不再作数
        channel.setLastTestedAt(null);
        channel.setLastTestSucceeded(null);
        channel.setLastTestMessage(null);
        channel.setStatus("ACTIVE");
        channel.setUpdatedAt(Instant.now());
        channel.setUpdatedBy(WorkspaceContext.require().userId());
        channelMapper.updateById(channel);
        return toView(channel);
    }

    @Transactional
    public void delete(String id) {
        requireChannel(id);
        channelMapper.deleteById(id);
    }

    @Transactional
    public ChannelView setEnabled(String id, boolean enabled) {
        AlertChannel channel = requireChannel(id);
        channel.setStatus(enabled ? "ACTIVE" : "DISABLED");
        channel.setUpdatedAt(Instant.now());
        channelMapper.updateById(channel);
        return toView(channel);
    }

    /**
     * 连通性测试(序号 33 的明确要求)。
     *
     * <p>真的发一条测试消息出去,而不是"检查配置格式对不对"。后者能通过而
     * 前者失败的情况太常见了:SMTP 端口被防火墙挡住、Webhook 的地址写对了
     * 但服务已经下线。
     */
    @Transactional
    public TestResult test(String id) {
        AlertChannel channel = requireChannel(id);
        Instant now = Instant.now();
        TestResult result = switch (channel.getType()) {
            case "EMAIL" -> testEmail(channel);
            case "WEBHOOK" -> testWebhook(channel);
            default -> new TestResult(false, "未知的渠道类型: " + channel.getType(), now);
        };

        channel.setLastTestedAt(now);
        channel.setLastTestSucceeded(result.succeeded());
        channel.setLastTestMessage(truncate(result.message()));
        // 测试失败就标成不可达 —— 它会显示在渠道列表上,而不是只留在一个
        // 关掉就没了的提示框里
        if (!"DISABLED".equals(channel.getStatus())) {
            channel.setStatus(result.succeeded() ? "ACTIVE" : "UNREACHABLE");
        }
        channel.setUpdatedAt(now);
        channelMapper.updateById(channel);

        return new TestResult(result.succeeded(), result.message(), now);
    }

    /**
     * 真的推送一条告警。
     *
     * <p>返回失败原因而不是抛异常:一个渠道发不出去,不该让其余渠道也收不到,
     * 更不该让告警本身的落库回滚。
     *
     * @return null 表示成功;否则是失败原因
     */
    public String deliver(String channelId, String title, String content) {
        AlertChannel channel = channelMapper.selectById(channelId);
        if (channel == null) {
            return "渠道不存在: " + channelId;
        }
        if ("DISABLED".equals(channel.getStatus())) {
            return "渠道已停用: " + channel.getName();
        }
        try {
            TestResult result = switch (channel.getType()) {
                case "EMAIL" -> sendEmail(channel, title, content);
                case "WEBHOOK" -> postWebhook(channel, Map.of(
                        "title", title, "content", content,
                        "timestamp", Instant.now().toString()));
                default -> new TestResult(false, "未知的渠道类型", Instant.now());
            };
            return result.succeeded() ? null : result.message();
        } catch (RuntimeException e) {
            log.warn("告警推送失败 channel={} ", channelId, e);
            return e.getMessage();
        }
    }

    // ── 各渠道的实现 ────────────────────────────────────────────────────

    private TestResult testEmail(AlertChannel channel) {
        return sendEmail(channel, "【数据治理平台】渠道连通性测试",
                "这是一封测试邮件,收到它说明渠道「%s」配置正确。".formatted(channel.getName()));
    }

    private TestResult sendEmail(AlertChannel channel, String subject, String body) {
        Instant now = Instant.now();
        if (mailSender == null) {
            // 没配 SMTP 时明确失败,而不是静默成功 —— 后者会让用户以为
            // 告警已经在发了,直到某天出事故才发现从来没发出去过
            return new TestResult(false,
                    "平台未配置 SMTP(spring.mail.host),邮件渠道无法发送", now);
        }
        Map<String, Object> config = readConfig(channel);
        List<String> recipients = stringList(config.get("recipients"));
        if (recipients.isEmpty()) {
            return new TestResult(false, "没有配置收件人", now);
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(recipients.toArray(String[]::new));
            message.setSubject(subject);
            message.setText(body);
            Object from = config.get("from");
            if (from != null && !from.toString().isBlank()) {
                message.setFrom(from.toString());
            }
            mailSender.send(message);
            return new TestResult(true, "已发送到 %d 个收件人".formatted(recipients.size()), now);
        } catch (RuntimeException e) {
            return new TestResult(false, "发送失败: " + rootMessage(e), now);
        }
    }

    private TestResult testWebhook(AlertChannel channel) {
        return postWebhook(channel, Map.of(
                "title", "【数据治理平台】渠道连通性测试",
                "content", "这是一条测试消息,收到它说明渠道「%s」配置正确。"
                        .formatted(channel.getName()),
                "test", true,
                "timestamp", Instant.now().toString()));
    }

    @SuppressWarnings("unchecked")
    private TestResult postWebhook(AlertChannel channel, Map<String, Object> payload) {
        Instant now = Instant.now();
        Map<String, Object> config = readConfig(channel);
        Object url = config.get("url");
        if (url == null || url.toString().isBlank()) {
            return new TestResult(false, "没有配置 Webhook 地址", now);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(TEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)));
            if (config.get("headers") instanceof Map<?, ?> headers) {
                ((Map<String, Object>) headers).forEach(
                        (k, v) -> builder.header(k, String.valueOf(v)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return new TestResult(ok,
                    ok ? "HTTP %d".formatted(response.statusCode())
                       : "HTTP %d: %s".formatted(response.statusCode(),
                            truncate(response.body())),
                    now);
        } catch (java.io.IOException e) {
            return new TestResult(false, "连接失败: " + rootMessage(e), now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "测试被中断", now);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private void validateConfig(String type, Map<String, Object> config) {
        Map<String, Object> effective = config == null ? Map.of() : config;
        if ("EMAIL".equals(type) && stringList(effective.get("recipients")).isEmpty()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "邮件渠道必须配置至少一个收件人");
        }
        if ("WEBHOOK".equals(type)) {
            Object url = effective.get("url");
            if (url == null || url.toString().isBlank()) {
                throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                        "Webhook 渠道必须配置地址");
            }
            String value = url.toString();
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                        "Webhook 地址必须以 http:// 或 https:// 开头");
            }
        }
    }

    private ChannelView toView(AlertChannel c) {
        return new ChannelView(c.getId(), c.getName(), c.getType(), c.getStatus(),
                describeTarget(c), c.getLastTestedAt(), c.getLastTestSucceeded(),
                c.getLastTestMessage(), c.getCreatedAt(), c.getCreatedBy());
    }

    /** 目标的可读描述。Webhook 只给主机名 —— 完整 URL 的查询串里可能有令牌。 */
    private String describeTarget(AlertChannel channel) {
        Map<String, Object> config = readConfig(channel);
        if ("EMAIL".equals(channel.getType())) {
            List<String> recipients = stringList(config.get("recipients"));
            return recipients.isEmpty() ? "(未配置)" : String.join(", ", recipients);
        }
        Object url = config.get("url");
        if (url == null) {
            return "(未配置)";
        }
        try {
            URI uri = URI.create(url.toString());
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
        } catch (IllegalArgumentException e) {
            return "(地址无效)";
        }
    }

    private Map<String, Object> readConfig(AlertChannel channel) {
        if (channel.getConfigJson() == null || channel.getConfigJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(channel.getConfigJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("渠道配置解析失败 id={}", channel.getId(), e);
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "渠道配置序列化失败");
        }
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            // 允许逗号分隔的一行字符串 —— 用户十有八九是这么填的
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }

    private void requireNameAvailable(String workspaceId, String name, String excludeId) {
        Long count = channelMapper.selectCount(new LambdaQueryWrapper<AlertChannel>()
                .eq(AlertChannel::getWorkspaceId, workspaceId)
                .eq(AlertChannel::getName, name == null ? "" : name.trim())
                .ne(excludeId != null, AlertChannel::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.conflict(ErrorCode.SYS_CONFLICT, "渠道名称已存在: " + name);
        }
    }

    AlertChannel requireChannel(String id) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        AlertChannel channel = channelMapper.selectOne(new LambdaQueryWrapper<AlertChannel>()
                .eq(AlertChannel::getId, id)
                .eq(AlertChannel::getWorkspaceId, workspaceId));
        if (channel == null) {
            throw BizException.notFound(ErrorCode.SYS_NOT_FOUND, "告警渠道 " + id);
        }
        return channel;
    }
}
