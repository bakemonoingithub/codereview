import type { ThemeConfig } from 'ant-design-vue/es/config-provider/context'

/**
 * Ant Design Vue 4.2 的组件 token 与原型指南里的别名不完全同名。
 * 对得上的写在这里；表格内边距、按钮阴影、输入框底色等由 global.less 补齐。
 */
export const theme: ThemeConfig = {
  token: {
    colorPrimary: '#2563eb',
    colorInfo: '#2563eb',
    colorSuccess: '#059669',
    colorWarning: '#b45309',
    colorError: '#dc2626',
    colorText: '#0f172a',
    colorTextSecondary: '#64748b',
    colorTextTertiary: '#94a3b8',
    colorBorder: '#e2e8f0',
    colorBorderSecondary: '#e8edf3',
    colorBgLayout: '#f8fafc',
    colorBgContainer: '#ffffff',
    colorFillAlter: '#fafbfc',
    colorLink: '#2563eb',
    colorLinkHover: '#1d4ed8',
    borderRadius: 12,
    borderRadiusLG: 20,
    borderRadiusSM: 8,
    fontFamily: '"Inter","PingFang SC","Hiragino Sans GB","Microsoft YaHei","Noto Sans CJK SC",system-ui,sans-serif',
    fontSize: 14,
    fontWeightStrong: 650,
    controlHeight: 38,
    boxShadow: '0 1px 2px #0f172a0a, 0 4px 16px #0f172a08'
  },
  components: {
    Layout: {
      colorBgHeader: 'rgba(255,255,255,0.8)',
      colorBgBody: '#f8fafc',
      colorBgTrigger: '#ffffff'
    },
    Menu: {
      colorItemBg: '#ffffff',
      colorItemText: '#475569',
      colorItemTextHover: '#0f172a',
      colorItemBgHover: '#f1f5f9',
      colorItemTextSelected: '#2563eb',
      colorItemBgSelected: '#eff4ff',
      colorItemBgActive: '#eff4ff',
      radiusItem: 12,
      colorGroupTitle: '#94a3b8',
      itemMarginInline: 0
    },
    Modal: {
      borderRadiusLG: 24
    },
    Card: {
      borderRadiusLG: 20
    },
    Tag: {
      borderRadiusSM: 999
    }
  }
}
