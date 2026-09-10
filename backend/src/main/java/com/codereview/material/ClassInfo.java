package com.codereview.material;

import java.util.List;

/** 结构抽取出的类/接口/枚举/record 信息。 */
public record ClassInfo(
        String path,
        String packageName,
        String name,
        String kind,
        List<String> annotations,
        List<String> extendedTypes,
        List<String> implementedTypes,
        List<Field> fields,
        List<String> methods) {

    public record Field(String type, String name) {
    }

    public String fqcn() {
        return packageName == null || packageName.isBlank() ? name : packageName + "." + name;
    }
}
