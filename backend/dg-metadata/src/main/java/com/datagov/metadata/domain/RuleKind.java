package com.datagov.metadata.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则种类(功能 17)。
 *
 * <p>需求原文列了两类共八种:<br>
 * <b>清洗</b> —— 时间/日期格式、数值格式、缺失值<br>
 * <b>转换</b> —— 字符串替换、大小写、前后缀、解密、去空格
 *
 * <p><b>规则是「双栖对象」</b>(function-space-matrix 序号 17):定义归 Metadata,
 * 解释执行归 Runtime。这个枚举是定义侧 —— 它描述"有哪些规则、每种要填什么参数",
 * 但完全不知道怎么执行。执行侧是 Runtime 的 {@code RuleInterpreter}。
 *
 * <p>这样切分的理由是架构风险 R6:同步任务<b>引用</b> ruleId,不内嵌规则实现。
 * 若把规则实现写进同步任务,同一条"手机号脱敏"规则会在十几个任务里各有一份
 * 略微不同的拷贝,而修一处漏九处。
 */
public enum RuleKind {

    // ── 清洗 ────────────────────────────────────────────────────────────

    /** 时间/日期格式:把源端的字符串日期规整成统一格式 */
    DATE_FORMAT("时间日期格式", Category.CLEANSE,
            Map.of("sourcePattern", "源格式,如 yyyy/MM/dd",
                    "targetPattern", "目标格式,如 yyyy-MM-dd")),

    /** 数值格式:保留小数位、去掉千分位 */
    NUMBER_FORMAT("数值格式", Category.CLEANSE,
            Map.of("scale", "保留小数位数",
                    "stripGrouping", "是否去掉千分位分隔符,true/false")),

    /** 缺失值:空值填充成默认值 */
    NULL_FILL("缺失值填充", Category.CLEANSE,
            Map.of("defaultValue", "空值时填什么",
                    "treatBlankAsNull", "空白字符串是否算空,true/false")),

    // ── 转换 ────────────────────────────────────────────────────────────

    /** 字符串替换 */
    STRING_REPLACE("字符串替换", Category.TRANSFORM,
            Map.of("search", "要替换的内容",
                    "replacement", "替换成什么",
                    "regex", "search 是否为正则,true/false")),

    /** 大小写 */
    CHANGE_CASE("大小写转换", Category.TRANSFORM,
            Map.of("mode", "UPPER / LOWER")),

    /** 前后缀 */
    AFFIX("增删前后缀", Category.TRANSFORM,
            Map.of("prefix", "要加的前缀",
                    "suffix", "要加的后缀",
                    "stripPrefix", "要去掉的前缀",
                    "stripSuffix", "要去掉的后缀")),

    /**
     * 解密。
     *
     * <p>参数里<b>只有密钥的引用</b>,不是密钥本身 —— 密钥归 Platform 的凭据托管。
     * 把密钥写进规则参数,等于让一份明文密钥躺在 md_rule.params_json 里,
     * 而规则定义是所有人都能看的。
     */
    DECRYPT("解密", Category.TRANSFORM,
            Map.of("algorithm", "AES_GCM / SM4",
                    "credentialId", "密钥所在的凭据 ID —— 不是密钥本身")),

    /** 去空格 */
    TRIM("去空格", Category.TRANSFORM,
            Map.of("mode", "BOTH / LEADING / TRAILING"));

    public enum Category {
        CLEANSE("清洗"),
        TRANSFORM("转换");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private final String displayName;
    private final Category category;
    private final Map<String, String> paramSpec;

    RuleKind(String displayName, Category category, Map<String, String> paramSpec) {
        this.displayName = displayName;
        this.category = category;
        this.paramSpec = new LinkedHashMap<>(paramSpec);
    }

    public String displayName() {
        return displayName;
    }

    public Category category() {
        return category;
    }

    /**
     * 参数名 → 说明。
     *
     * <p>由后端下发给 UI 渲染参数表单 —— 前端不硬编码每种规则的字段。
     * 加一种规则时只改这个枚举,界面自动跟上。
     */
    public Map<String, String> paramSpec() {
        return Map.copyOf(paramSpec);
    }

    /** 必填参数。缺了它规则跑起来一定不对,所以在定义保存时就拦。 */
    public List<String> requiredParams() {
        return switch (this) {
            case DATE_FORMAT -> List.of("targetPattern");
            case STRING_REPLACE -> List.of("search");
            case CHANGE_CASE -> List.of("mode");
            case DECRYPT -> List.of("algorithm", "credentialId");
            // 其余规则的参数都有合理默认值:缺失值填充默认填空串、
            // 去空格默认两端、数值格式默认不改。强制必填只会增加无谓的输入
            default -> List.of();
        };
    }
}
