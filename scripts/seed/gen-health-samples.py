#!/usr/bin/env python3
"""生成文件数据源(功能 2、12)的测试素材 —— 健康医疗主题。

<b>为什么要自己生成,而不是只用公开数据集</b>:公开的健康数据集里没有一份
能同时满足下面三个条件 ——

  1. 含"看起来像敏感个人信息、但确实不是真人"的字段(验证 MASK 必须的);
  2. 是中文列名 + GBK 编码(国内政务数据的常态,而平台至今只在自己造的
     干净 UTF-8 数据上跑过);
  3. 可以进版本库、可以随便发。

Synthea 满足第 1 条(MITRE 明确声明其合成病人无隐私与法律限制),但它是
美国数据:SSN 是 9 位,不是 18 位身份证,也没有中文。而网上流传的所谓
"测试身份证数据集"绝大多数是泄露数据 —— 那种东西不该出现在任何仓库里。

所以这里的做法是:Synthea 官方样本能下就下(见 seed-public-datasets.sh),
下不到就用本脚本生成同结构替身;中文那一份则<b>一定</b>是本脚本生成的,
身份证号按 GB 11643-1999 的 mod-11-2 算出合法校验位,但号段与出生日期
组合是随机的,不对应任何真人。

输出确定:同一个 --seed 反复跑得到逐字节相同的文件,验证脚本才能对结果
做精确断言。
"""
import argparse
import csv
import json
import os
import random
import sys
from datetime import date, timedelta

# ── Synthea patients.csv 的列(取其常用子集)────────────────────────────
# 完整定义见 https://github.com/synthetichealth/synthea/wiki/CSV-File-Data-Dictionary
SYNTHEA_COLUMNS = [
    "Id", "BIRTHDATE", "DEATHDATE", "SSN", "DRIVERS", "PASSPORT",
    "PREFIX", "FIRST", "LAST", "MARITAL", "RACE", "ETHNICITY", "GENDER",
    "BIRTHPLACE", "ADDRESS", "CITY", "STATE", "ZIP",
    "HEALTHCARE_EXPENSES", "HEALTHCARE_COVERAGE", "INCOME",
]

FIRST_NAMES = ["James", "Mary", "Robert", "Patricia", "John", "Jennifer",
               "Michael", "Linda", "David", "Elizabeth", "William", "Barbara"]
LAST_NAMES = ["Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia",
              "Miller", "Davis", "Rodriguez", "Martinez", "Wilson", "Anderson"]
CITIES = [("Boston", "MA", "02108"), ("Worcester", "MA", "01602"),
          ("Springfield", "MA", "01103"), ("Cambridge", "MA", "02139")]

# ── 中文样本 ──────────────────────────────────────────────────────────
XING = list("王李张刘陈杨黄赵吴周徐孙马朱胡林郭何高罗")
MING = list("伟芳娜秀英敏静丽强磊洋艳勇军杰娟涛明超秀霞平")
# 行政区划码取自公开的 GB/T 2260 江苏盐城段 —— 只是号段前缀,不指向任何人
DISTRICT_CODES = ["320902", "320903", "320981", "320902", "320904"]
DIAGNOSES = ["原发性高血压", "2型糖尿病", "慢性阻塞性肺疾病",
             "冠状动脉粥样硬化性心脏病", "上呼吸道感染", "急性胃肠炎"]
HOSPITALS = ["亭湖区人民医院", "盐城市第一人民医院", "亭湖区新兴镇卫生院",
             "盐城市中医院", "亭湖区五星街道社区卫生服务中心"]

ID_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
ID_CHECK = "10X98765432"


def id_card(rng):
    """生成校验位合法的 18 位身份证号(GB 11643-1999 mod-11-2)。

    校验位必须算对 —— 否则脱敏规则测试会退化成"对一串乱码做字符串截取",
    验证不了真实场景里那种"前 6 位后 4 位保留、中间打码"的规则是否踩对了位置。
    """
    body = (rng.choice(DISTRICT_CODES)
            + (date(1940, 1, 1) + timedelta(days=rng.randint(0, 30000))).strftime("%Y%m%d")
            + f"{rng.randint(0, 999):03d}")
    total = sum(int(c) * w for c, w in zip(body, ID_WEIGHTS))
    return body + ID_CHECK[total % 11]


def mobile(rng):
    prefix = rng.choice(["139", "138", "150", "158", "182", "187", "199"])
    return prefix + f"{rng.randint(0, 99999999):08d}"


def gen_synthea_like(rng, count):
    rows = []
    for i in range(count):
        birth = date(1935, 1, 1) + timedelta(days=rng.randint(0, 32000))
        deceased = rng.random() < 0.08
        city, state, zipcode = rng.choice(CITIES)
        rows.append({
            "Id": f"{rng.getrandbits(32):08x}-{rng.getrandbits(16):04x}-"
                  f"{rng.getrandbits(16):04x}-{rng.getrandbits(48):012x}",
            "BIRTHDATE": birth.isoformat(),
            # 空值刻意留空字符串而不是 NULL —— CSV 里根本没有 NULL 这个概念,
            # 平台怎么处理空串是必须被测到的行为
            "DEATHDATE": (birth + timedelta(days=rng.randint(20000, 33000))).isoformat()
                         if deceased else "",
            "SSN": f"999-{rng.randint(10, 99)}-{rng.randint(1000, 9999)}",
            "DRIVERS": f"S999{rng.randint(10000, 99999)}" if not deceased else "",
            "PASSPORT": f"X{rng.randint(10000000, 99999999)}X",
            "PREFIX": rng.choice(["Mr.", "Mrs.", "Ms.", ""]),
            "FIRST": rng.choice(FIRST_NAMES) + str(rng.randint(100, 999)),
            "LAST": rng.choice(LAST_NAMES) + str(rng.randint(100, 999)),
            "MARITAL": rng.choice(["M", "S", ""]),
            "RACE": rng.choice(["white", "black", "asian", "native", "other"]),
            "ETHNICITY": rng.choice(["hispanic", "nonhispanic"]),
            "GENDER": rng.choice(["M", "F"]),
            "BIRTHPLACE": f"{city} {state} US",
            "ADDRESS": f"{rng.randint(1, 999)} {rng.choice(LAST_NAMES)} Street",
            "CITY": city,
            "STATE": state,
            "ZIP": zipcode,
            "HEALTHCARE_EXPENSES": f"{rng.uniform(1000, 900000):.2f}",
            "HEALTHCARE_COVERAGE": f"{rng.uniform(0, 40000):.2f}",
            "INCOME": str(rng.randint(8000, 250000)),
        })
    return rows


def gen_chinese(rng, count):
    rows = []
    for i in range(count):
        idc = id_card(rng)
        birth = f"{idc[6:10]}-{idc[10:12]}-{idc[12:14]}"
        visit = date(2024, 1, 1) + timedelta(days=rng.randint(0, 600))
        rows.append({
            "姓名": rng.choice(XING) + rng.choice(MING) + (rng.choice(MING) if rng.random() < 0.4 else ""),
            "性别": "男" if int(idc[16]) % 2 else "女",
            "出生日期": birth,
            "身份证号": idc,
            "手机号": mobile(rng),
            "家庭住址": f"江苏省盐城市亭湖区{rng.choice(['解放路', '文港路', '希望大道', '建军路'])}"
                        f"{rng.randint(1, 999)}号",
            "就诊机构": rng.choice(HOSPITALS),
            "就诊日期": visit.isoformat(),
            # 空值三种写法混在一起 —— 政务数据的真实样子。平台把 "—"/"无"
            # 当成有效字符串写进去,还是当成空,是必须被看见的行为
            "诊断": rng.choice(DIAGNOSES) if rng.random() > 0.12 else rng.choice(["—", "无", ""]),
            "费用金额": f"{rng.uniform(12, 8800):.2f}",
        })
    return rows


def write_csv(path, columns, rows, encoding="utf-8", newline_style="\n"):
    with open(path, "w", encoding=encoding, newline="") as f:
        writer = csv.DictWriter(f, fieldnames=columns, lineterminator=newline_style)
        writer.writeheader()
        writer.writerows(rows)
    return os.path.getsize(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="输出目录")
    parser.add_argument("--count", type=int, default=200, help="每份样本的记录数")
    parser.add_argument("--seed", type=int, default=20260908,
                        help="随机种子;固定它才能让验证脚本做精确断言")
    parser.add_argument("--synthea-standin", action="store_true",
                        help="同时生成 Synthea 结构的替身(官方样本下不到时用)")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    rng = random.Random(args.seed)
    produced = []

    if args.synthea_standin:
        rows = gen_synthea_like(rng, args.count)
        path = os.path.join(args.out, "patients.csv")
        size = write_csv(path, SYNTHEA_COLUMNS, rows)
        produced.append(("patients.csv", "Synthea 结构替身(本地生成)", size, "UTF-8"))

    cn_rows = gen_chinese(rng, args.count)
    cn_columns = list(cn_rows[0].keys())

    utf8_path = os.path.join(args.out, "patients_cn_utf8.csv")
    size = write_csv(utf8_path, cn_columns, cn_rows)
    produced.append(("patients_cn_utf8.csv", "中文表头 · 合成身份证/手机号", size, "UTF-8"))

    # GBK 那一份是对照组:内容逐字段相同,只有编码不同。
    # 两份都跑一遍,才能把"乱码"和"解析逻辑出错"区分开 —— 只有一份的话,
    # 结果不对时根本判断不了是编码没生效还是列对不上。
    gbk_path = os.path.join(args.out, "patients_cn_gbk.csv")
    size = write_csv(gbk_path, cn_columns, cn_rows, encoding="gbk")
    produced.append(("patients_cn_gbk.csv", "同上,GBK 编码(对照组)", size, "GBK"))

    # JSON 那一份用来测 jsonPath:数组不在顶层,而是嵌在 data.items 下面。
    # 顶层就是数组的情况太容易蒙对了,测不出路径解析有没有真的实现。
    obs = {
        "code": 0,
        "message": "success",
        "data": {
            "total": len(cn_rows),
            "items": [{
                "身份证号": r["身份证号"],
                "就诊日期": r["就诊日期"],
                "诊断": r["诊断"],
                "费用金额": r["费用金额"],
            } for r in cn_rows],
        },
    }
    json_path = os.path.join(args.out, "observations.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(obs, f, ensure_ascii=False, indent=2)
    produced.append(("observations.json", "嵌套数组,jsonPath = data.items",
                     os.path.getsize(json_path), "UTF-8"))

    for name, desc, size, enc in produced:
        print(f"  {name:<24} {size:>9,} B  {enc:<5}  {desc}")
    print(f"\n  共 {len(produced)} 份,每份 {args.count} 条,seed={args.seed}(可复现)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
