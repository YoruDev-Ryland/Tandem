package dev.emusync.app.shell;

oneway interface IWatchListener {
    void onChanged(String dir, String name);
}
