package com.codereview.material;

/** 依赖边：from → to，均为项目内部类的全限定名。 */
public record DepEdge(String from, String to) {
}
