package com.codereview.dto;

import java.util.List;

/**
 * 文件树节点。
 *
 * @param reviewable 是否落在可审查名单内。**由后端唯一判定**（{@link com.codereview.common.ReviewableFiles}），
 *                   前端直接读，不自己维护扩展名表 —— 否则两处判定早晚漂移。
 *                   目录节点无意义（不参与审查），按同一个函数算即可。
 */
public record TreeNodeResp(String path, String name, String type, boolean reviewable,
                           List<TreeNodeResp> children) {
}
