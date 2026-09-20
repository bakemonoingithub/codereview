package com.codereview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codereview.common.BusinessException;
import com.codereview.common.PageLimits;
import com.codereview.dto.PromptCreateReq;
import com.codereview.dto.PromptUpdateReq;
import com.codereview.entity.Prompt;
import com.codereview.entity.PromptVersion;
import com.codereview.mapper.PromptMapper;
import com.codereview.mapper.PromptVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提示词名称判重（V5 移除 {@code uk_name} 后由应用层承接）。
 *
 * <p>注：{@code verify(...).insert(...)} 必须用**带类型**的匹配器。MyBatis-Plus 3.5.7 的
 * {@code BaseMapper} 同时有 {@code insert(T)} 与 {@code insert(Collection<T>)} 两个重载，
 * 裸 {@code any()} 会因歧义而编译失败。
 */
class PromptServiceTest {

    private PromptMapper promptMapper;
    private PromptVersionMapper versionMapper;
    private PromptService service;

    @BeforeEach
    void setUp() {
        promptMapper = mock(PromptMapper.class);
        versionMapper = mock(PromptVersionMapper.class);
        service = new PromptService(promptMapper, versionMapper);
    }

    @Test
    void rejectsDuplicateNameBeforeWritingAnything() {
        when(promptMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(new PromptCreateReq("重名", "描述", List.of(), "正文")));

        assertEquals(1005, e.getCode());
        // 判重必须发生在两次写入之前，否则会留下"提示词已建、版本没建"的半截数据
        verify(promptMapper, never()).insert(any(Prompt.class));
        verify(versionMapper, never()).insert(any(PromptVersion.class));
    }

    @Test
    void rejectsRenameThatCollidesWithAnother() {
        Prompt existing = new Prompt();
        existing.setId(1L);
        existing.setName("原名");
        when(promptMapper.selectById(1L)).thenReturn(existing);
        when(promptMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(1L, new PromptUpdateReq("已占用", "描述", List.of())));

        assertEquals(1005, e.getCode());
        verify(promptMapper, never()).updateById(any(Prompt.class));
    }

    @Test
    void allowsKeepingOwnNameOnUpdate() {
        Prompt existing = new Prompt();
        existing.setId(1L);
        existing.setName("原名");
        when(promptMapper.selectById(1L)).thenReturn(existing);
        when(promptMapper.selectCount(any())).thenReturn(0L);

        Prompt updated = service.update(1L, new PromptUpdateReq("原名", "描述", List.of()));

        assertEquals("原名", updated.getName());
    }

    @Test
    void listClampsOversizedPageSizeToTheDocumentedCap() {
        when(promptMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.list(1, 100_000, null);

        ArgumentCaptor<Page<Prompt>> captor = ArgumentCaptor.forClass(Page.class);
        verify(promptMapper).selectPage(captor.capture(), any());
        assertEquals(PageLimits.MAX_PAGE_SIZE, captor.getValue().getSize());
        assertEquals(1, captor.getValue().getCurrent());
    }

    @Test
    void listFallsBackToOneForNonPositivePageNum() {
        when(promptMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.list(-5, 20, "关键字");

        ArgumentCaptor<Page<Prompt>> captor = ArgumentCaptor.forClass(Page.class);
        verify(promptMapper).selectPage(captor.capture(), any());
        assertEquals(1, captor.getValue().getCurrent());
        assertEquals(20, captor.getValue().getSize(), "合法页大小不该被改写");
    }
}
