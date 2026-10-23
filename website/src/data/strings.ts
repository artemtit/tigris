/* UI strings that live in the DOM as attributes or script text rather than in
   the page copy. Keeping them here is what makes the shared client script
   speak Chinese on / and English on /en/. */
import type { Locale } from "./lang";

export interface UiStrings {
  skip: string;
  navLabel: string;
  menuLabel: string;
  menuCloseLabel: string;
  copy: string;
  copied: string;
  copyFailed: string;
  compareName: string;
  compareValue: (p: number) => string;
  prev: string;
  next: string;
  close: string;
  viewer: string;
  pauseClips: string;
  playClips: string;
}

export const uiStrings: Record<Locale, UiStrings> = {
  zh: {
    skip: "跳到主要内容",
    navLabel: "主导航",
    menuLabel: "打开菜单",
    menuCloseLabel: "关闭菜单",
    copy: "复制",
    copied: "已复制",
    copyFailed: "复制失败",
    compareName: "对比国产电视桌面截图与 Tigris 电视实录",
    compareValue: (p) => `显示 ${p}% 的安装前画面`,
    prev: "上一张",
    next: "下一张",
    close: "关闭",
    viewer: "截图查看器",
    pauseClips: "暂停录屏",
    playClips: "播放录屏",
  },
  en: {
    skip: "Skip to content",
    navLabel: "Main",
    menuLabel: "Open menu",
    menuCloseLabel: "Close menu",
    copy: "Copy",
    copied: "Copied",
    copyFailed: "Copy failed",
    compareName: "Compare a user-supplied TV screenshot with a Tigris TV recording",
    compareValue: (p) => `Showing ${p}% of the before image`,
    prev: "Previous",
    next: "Next",
    close: "Close",
    viewer: "Screenshot viewer",
    pauseClips: "Pause recordings",
    playClips: "Play recordings",
  },
};
