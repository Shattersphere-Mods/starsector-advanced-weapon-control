package com.dp.advancedgunnerycontrol.gui.suggesttaggui

import com.dp.advancedgunnerycontrol.gui.AGCGUI
import com.dp.advancedgunnerycontrol.gui.ButtonBase
import com.dp.advancedgunnerycontrol.gui.CampaignButtonCallbackPoller
import com.dp.advancedgunnerycontrol.gui.CampaignButtonSuppressionSnapshot
import com.dp.advancedgunnerycontrol.gui.CampaignGuiStyle
import com.dp.advancedgunnerycontrol.gui.GUIShower
import com.dp.advancedgunnerycontrol.gui.RenderedCampaignConfirmationModal
import com.dp.advancedgunnerycontrol.gui.addCampaignButtonControl
import com.dp.advancedgunnerycontrol.gui.addRenderedConfirmationModalButtons
import com.dp.advancedgunnerycontrol.gui.clearRenderedConfirmationModalButtons
import com.dp.advancedgunnerycontrol.gui.processCampaignButtonRightClickInput
import com.dp.advancedgunnerycontrol.gui.replaceCampaignContentWithErrorFallback
import com.dp.advancedgunnerycontrol.gui.suppressCampaignButtonHoverSnapshot
import com.dp.advancedgunnerycontrol.settings.Settings
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.InteractionDialogPlugin
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.combat.EngagementResultAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import org.lwjgl.input.Keyboard

/**
 * Standalone campaign Customize Suggested Tags dialog.
 * Used when editing global/suggested weapon presets outside the ship-editor page.
 */
class SuggestedTagGui : InteractionDialogPlugin {
    private inner class SuggestedTagDialogDelegate(
        private val panelPlugin: SuggestedTagPanelPlugin,
    ) : CustomVisualDialogDelegate {
        override fun init(panel: CustomPanelAPI, callbacks: CustomVisualDialogDelegate.DialogCallbacks) {
            panelPlugin.init(panel, callbacks)
        }

        override fun getCustomPanelPlugin(): CustomUIPanelPlugin = panelPlugin
        override fun getNoiseAlpha(): Float = 0f
        override fun advance(amount: Float) {}
        override fun reportDismissed(option: Int) {}
    }

    /**
     * Campaign suggested-tags host component.
     * Owns the dialog root, option buttons, confirmation popup, and shared
     * SuggestedTagEditorState content rebuilds.
     */
    private inner class SuggestedTagPanelPlugin : BaseCustomUIPanelPlugin() {
        private val log = Global.getLogger(SuggestedTagPanelPlugin::class.java)
        private var panel: CustomPanelAPI? = null
        private var callbacks: CustomVisualDialogDelegate.DialogCallbacks? = null
        private var contentPanel: CustomPanelAPI? = null
        private var view: SuggestedTagGuiView? = null
        private val actionButtons = mutableListOf<ButtonBase<*>>()
        private val actionButtonPoller = CampaignButtonCallbackPoller(actionButtons)
        private var confirmationRootPanel: CustomPanelAPI? = null
        private var confirmationModal: RenderedCampaignConfirmationModal? = null
        private var confirmationButtonStartIndex: Int = -1
        private var confirmationButtonEndIndex: Int = -1
        private var nonModalSuppressionSnapshot: CampaignButtonSuppressionSnapshot? = null

        fun init(panel: CustomPanelAPI, callbacks: CustomVisualDialogDelegate.DialogCallbacks) {
            this.panel = panel
            this.callbacks = callbacks
            rebuild()
        }

        override fun advance(amount: Float) {
            actionButtonPoller.processIfRequested()
            if (editorState.shouldRegenerate(view)) {
                rebuild()
            }
        }

        override fun processInput(events: MutableList<InputEventAPI>) {
            actionButtonPoller.requestPollFromInput(events)
            if (actionButtons.processCampaignButtonRightClickInput(events)) return
            editorState.processInput(
                events = events,
                view = view,
                panelPos = contentPanel?.position,
                closeKeys = setOf(Keyboard.KEY_ESCAPE, Settings.guiHotkey()),
                onClose = { backToWeaponGroups(callbacks) },
                rebuild = ::rebuild,
                refreshContent = ::refreshContentOrRebuild,
                requestConfirmationRefresh = ::openConfirmationModalOrRebuild,
                closeConfirmationTargeted = ::closeConfirmationModalTargeted,
            )
        }

        override fun buttonPressed(buttonId: Any?) {}

        private fun executeAction(action: SuggestedTagUiAction) {
            editorState.executeAction(
                action,
                rebuild = ::rebuild,
                refreshContent = ::refreshContentOrRebuild,
                requestConfirmationRefresh = ::openConfirmationModalOrRebuild,
            )
        }

        private fun rebuild() {
            val root = panel ?: return
            val startNs = System.nanoTime()
            try {
                val content = editorState.rebuildContent(
                    root = root,
                    content = contentPanel,
                    view = view,
                    onCleared = ::clearContentRefs,
                    buildOptionsPanel = ::buildOptionsPanel,
                    renderConfirmationModal = ::renderConfirmationModal,
                )
                contentPanel = content.panel
                view = content.view
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                if (elapsedMs >= CampaignGuiStyle.GUI_PERF_LOG_THRESHOLD_MS) {
                    log.info("[AGC_PERF] Campaign suggested-tags rebuild took ${elapsedMs}ms")
                }
            } catch (ex: Throwable) {
                log.error("[AGC_SUGGESTED_UI] rebuild failed", ex)
                buildFallback(root, ex)
            }
        }

        private fun buildFallback(root: CustomPanelAPI, ex: Throwable) {
            contentPanel = replaceCampaignContentWithErrorFallback(
                root = root,
                content = contentPanel,
                title = "AGC Suggested Tags UI Error",
                message = "The suggested-tags editor failed to build. Press [Esc] to return.",
                reason = "Reason: ${ex.javaClass.simpleName}: ${ex.message ?: "no message"}",
                highlightToken = "[Esc]",
                onCleared = ::clearContentRefs,
            )
        }

        private fun clearContentRefs() {
            clearConfirmationModalRefs()
            nonModalSuppressionSnapshot = null
            contentPanel = null
            view = null
        }

        private fun buildOptionsPanel(panel: CustomPanelAPI) {
            actionButtons.clear()
            editorState.renderOptionsPanel(
                panel = panel,
                bindButton = { action, button, stateful ->
                    bindActionButton(action, button, stateful)
                },
            )
        }

        private fun refreshContentOrRebuild() {
            editorState.refreshContent(
                view = view,
                buildOptionsPanel = ::buildOptionsPanel,
                rebuild = ::rebuild,
            )
        }

        private fun bindActionButton(
            action: SuggestedTagUiAction,
            button: ButtonAPI,
            stateful: Boolean,
        ) {
            val checkedVisualState = action.usesCheckedVisualState(stateful)
            val control = actionButtons.addCampaignButtonControl(
                button = button,
                active = action.active && checkedVisualState,
                stateful = checkedVisualState,
            ) {
                executeAction(action)
            }
            action.onRightClick?.let { rightClick ->
                control.onRightClick {
                    rightClick()
                    refreshContentOrRebuild()
                }
            }
        }

        private fun renderConfirmationModal(root: CustomPanelAPI) {
            val startIndex = actionButtons.size
            val modal = com.dp.advancedgunnerycontrol.gui.suggesttaggui.renderSuggestedConfirmationModal(
                root = root,
                request = editorState.pendingConfirmationRequest(),
                ids = CAMPAIGN_SUGGESTED_CONFIRMATION_IDS,
                suppressNonModalButtonHover = ::suppressNonModalButtonHover,
            ) { modal, request ->
                actionButtons.addRenderedConfirmationModalButtons(
                    modal = modal,
                    onConfirm = {
                        request.onConfirm()
                        rebuild()
                    },
                    onCancel = {
                        request.onCancel()
                        closeConfirmationModalTargeted()
                    },
                )
            } ?: return
            confirmationRootPanel = root
            confirmationModal = modal
            confirmationButtonStartIndex = startIndex
            confirmationButtonEndIndex = actionButtons.size
        }

        private fun suppressNonModalButtonHover() {
            if (nonModalSuppressionSnapshot != null) return
            nonModalSuppressionSnapshot = actionButtons.suppressCampaignButtonHoverSnapshot()
        }

        private fun openConfirmationModalOrRebuild() {
            val root = contentPanel ?: run {
                rebuild()
                return
            }
            if (confirmationModal != null) {
                closeConfirmationModalTargeted()
            }
            renderConfirmationModal(root)
        }

        private fun closeConfirmationModalTargeted() {
            val root = confirmationRootPanel
            val modal = confirmationModal
            if (root != null && modal != null) {
                try {
                    runCatching { root.removeComponent(modal.backdrop) }
                        .onFailure { ex ->
                            Global.getLogger(SuggestedTagGui::class.java)
                                .warn("[AGC_SUGGESTED_TAGS] Failed to remove confirmation backdrop", ex)
                        }
                    runCatching { root.removeComponent(modal.dialog) }
                        .onFailure { ex ->
                            Global.getLogger(SuggestedTagGui::class.java)
                                .warn("[AGC_SUGGESTED_TAGS] Failed to remove confirmation dialog", ex)
                        }
                    actionButtons.clearRenderedConfirmationModalButtons(
                        confirmationButtonStartIndex,
                        confirmationButtonEndIndex,
                    )
                } finally {
                    clearConfirmationModalRefs()
                    restoreSuppressedNonModalButtonHover()
                }
            } else {
                rebuild()
                return
            }
        }

        private fun clearConfirmationModalRefs() {
            confirmationRootPanel = null
            confirmationModal = null
            confirmationButtonStartIndex = -1
            confirmationButtonEndIndex = -1
        }

        private fun restoreSuppressedNonModalButtonHover() {
            nonModalSuppressionSnapshot?.restore()
            nonModalSuppressionSnapshot = null
        }
    }

    private var dialog: InteractionDialogAPI? = null
    private val editorState = SuggestedTagEditorState()

    companion object {
        private val CAMPAIGN_SUGGESTED_CONFIRMATION_IDS = SuggestedConfirmationModalIds(
            confirm = "suggested_confirmation_modal_confirm",
            cancel = "suggested_confirmation_modal_cancel",
            backdrop = "suggested_confirmation_modal_backdrop",
        )
    }

    override fun init(interactionDialog: InteractionDialogAPI?) {
        editorState.ensureSuggestedTagsInitialized()
        dialog = interactionDialog
        openCustomEditor()
    }

    private fun openCustomEditor() {
        dialog?.textPanel?.clear()
        dialog?.optionPanel?.clearOptions()
        dialog?.hideTextPanel()
        dialog?.hideVisualPanel()
        dialog?.setPromptText("")
        val panelPlugin = SuggestedTagPanelPlugin()
        dialog?.showCustomVisualDialog(
            Global.getSettings().screenWidth.toFloat(),
            Global.getSettings().screenHeight.toFloat(),
            SuggestedTagDialogDelegate(panelPlugin)
        )
    }

    private fun backToWeaponGroups(callbacks: CustomVisualDialogDelegate.DialogCallbacks?) {
        callbacks?.dismissDialog()
        dialog?.dismiss()
        GUIShower.shouldOpenAgcGui = true
    }

    override fun optionSelected(optionText: String?, optionData: Any?) {}
    override fun advance(amount: Float) {}
    override fun optionMousedOver(optionText: String?, optionData: Any?) {}
    override fun backFromEngagement(result: EngagementResultAPI?) {}
    override fun getContext(): Any? = null
    override fun getMemoryMap(): MutableMap<String, MemoryAPI>? = null
}
