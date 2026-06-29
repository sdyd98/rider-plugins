package com.example.logview.ssh

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level persisted list of [SshProfile]s (the host metadata; secrets live in [SshSecrets]).
 * Stored in `logviewer-connections.xml` in the IDE config dir so connections are shared across all
 * projects. Access via [getInstance].
 */
@State(name = "LogViewerConnections", storages = [Storage("logviewer-connections.xml")])
class RemoteConnectionStore : PersistentStateComponent<RemoteConnectionStore.State> {

    class State {
        var profiles: MutableList<SshProfile> = mutableListOf()
        /** Local directories the user added as log roots (browsed as a file list in the sources tree). */
        var localRoots: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    val profiles: List<SshProfile> get() = state.profiles
    val localRoots: List<String> get() = state.localRoots

    fun addLocalRoot(path: String) {
        state.localRoots.removeAll { it == path }
        state.localRoots.add(path)
    }

    fun removeLocalRoot(path: String) {
        state.localRoots.removeAll { it == path }
    }

    fun add(profile: SshProfile) {
        state.profiles.removeAll { it.id == profile.id }
        state.profiles.add(profile)
    }

    /** Removes the profile metadata only. The caller clears the secret ([SshSecrets.clear]) OFF the EDT. */
    fun remove(id: String) {
        state.profiles.removeAll { it.id == id }
    }

    fun find(id: String): SshProfile? = state.profiles.firstOrNull { it.id == id }

    companion object {
        fun getInstance(): RemoteConnectionStore =
            ApplicationManager.getApplication().getService(RemoteConnectionStore::class.java)
    }
}
