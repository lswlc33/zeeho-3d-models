package io.github.lswlc33.zeehowebnext;

/** 主界面是否在前台。后台轮询/定时任务在别的进程线程里读它，必须 volatile。 */
final class AppState {
    static volatile boolean foreground;

    private AppState() {
    }
}
