package com.codereview.service;

import com.codereview.common.BusinessException;
import com.codereview.dto.ModelConfigReq;
import com.codereview.entity.ModelConfig;
import com.codereview.llm.LlmClient;
import com.codereview.mapper.ModelConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型名称判重（V5 移除 {@code uk_name} 后由应用层承接）。
 *
 * <p>覆盖两侧：**创建**与**重命名**。只校验创建是不够的 —— 那样"重命名"就成了
 * 绕过唯一性的后门，比加索引之前更糟。
 *
 * <p>注：{@code verify(...).insert(...)} 必须用**带类型**的匹配器。MyBatis-Plus 3.5.7 的
 * {@code BaseMapper} 同时有 {@code insert(T)} 与 {@code insert(Collection<T>)} 两个重载，
 * 裸 {@code any()} 会因歧义而编译失败。
 */
class ModelConfigServiceTest {

    private ModelConfigMapper modelConfigMapper;
    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        modelConfigMapper = mock(ModelConfigMapper.class);
        service = new ModelConfigService(modelConfigMapper, mock(LlmClient.class));
    }

    @Test
    void rejectsDuplicateNameOnCreate() {
        when(modelConfigMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(new ModelConfigReq("重名", "http://x", "t", "m", null)));

        assertEquals(1005, e.getCode());
        verify(modelConfigMapper, never()).insert(any(ModelConfig.class));
    }

    @Test
    void rejectsRenameThatCollidesWithAnother() {
        ModelConfig existing = new ModelConfig();
        existing.setId(1L);
        existing.setName("原名");
        when(modelConfigMapper.selectById(1L)).thenReturn(existing);
        when(modelConfigMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(1L, new ModelConfigReq("已占用", "http://x", null, "m", null)));

        assertEquals(1005, e.getCode());
        verify(modelConfigMapper, never()).updateById(any(ModelConfig.class));
    }

    @Test
    void allowsKeepingOwnNameOnUpdate() {
        ModelConfig existing = new ModelConfig();
        existing.setId(1L);
        existing.setName("原名");
        when(modelConfigMapper.selectById(1L)).thenReturn(existing);
        // 排除自身后不应命中任何记录 —— 否则"只改地址不改名"会被自己判为重复
        when(modelConfigMapper.selectCount(any())).thenReturn(0L);

        ModelConfig updated = service.update(1L, new ModelConfigReq("原名", "http://x", null, "m", null));

        assertEquals("原名", updated.getName());
    }
}
