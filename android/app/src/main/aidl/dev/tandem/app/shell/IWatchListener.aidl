package dev.tandem.app.shell;

oneway interface IWatchListener {
    void onChanged(String dir, String name);
}
