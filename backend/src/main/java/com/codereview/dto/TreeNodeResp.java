package com.codereview.dto;

import java.util.List;

public record TreeNodeResp(String path, String name, String type, List<TreeNodeResp> children) {
}
