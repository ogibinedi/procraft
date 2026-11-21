package io.github.artcraftandroid.procraft.canvas

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.core.Adjustment
import io.github.artcraftandroid.procraft.core.LayerInfo
import io.github.artcraftandroid.procraft.core.TextAlignment
import io.github.artcraftandroid.procraft.core.TextInfo
import io.github.artcraftandroid.procraft.core.TextSide
import io.github.artcraftandroid.procraft.core.TextStyleChange
import io.github.artcraftandroid.procraft.core.textIndexFromUtf16
import io.github.artcraftandroid.procraft.core.textIndexToUtf16
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.*

/** The letters chosen in the text field as the core counts them (not UTF-16): first and last. */
internal fun CanvasViewModel.textChosen(v: TextFieldValue = textField): Pair<UInt, UInt> =
    textIndexFromUtf16(v.text, v.selection.min.toUInt()) to textIndexFromUtf16(v.text, v.selection.max.toUInt())

internal fun CanvasViewModel.refreshTextLook() {
    val (start, end) = textChosen()
    textLook = session?.textLook(start, end)
}

/**
 * The look of the letters chosen changed by [change] (theirs alone); with only a caret, the look
 * of what is typed there next.
 */
fun CanvasViewModel.restyleText(change: TextStyleChange) {
    val (start, end) = textChosen()
    val now = session?.textRestyle(start, end, change) ?: return
    textInfo = now
    renderText()
    refreshTextLook()
}

/** The canvas's dots per inch. */
fun CanvasViewModel.canvasDpi(): Float = session?.info()?.dpi?.coerceAtLeast(1f) ?: 132f

fun CanvasViewModel.openTextStyle() {
    textBeforeStyle = textInfo
    textStyleOpen = true
}

/** The style sheet's Cancel: its changes go back (the text, where it is and its width stay), and the keyboard returns. */
fun CanvasViewModel.cancelTextStyle() {
    textBeforeStyle?.let { before -> changeText { before.copy(text = it.text, x = it.x, y = it.y, width = it.width) } }
    textStyleOpen = false
}

/** Copies the selected text (all of it if none is), and with [cut] takes it out. */
fun CanvasViewModel.copyText(cut: Boolean) {
    val v = textField
    val sel = if (v.selection.collapsed) TextRange(0, v.text.length) else v.selection
    val part = v.text.substring(sel.min, sel.max)
    if (part.isEmpty()) return
    // a system clipboard that refuses it: the words stay where they are, cut or not
    if (runCatching { systemClipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Text", part)) }.isFailure) {
        showNotice("The system clipboard didn't take the text.", warning = true)
        return
    }
    if (cut) changeTextField(TextFieldValue(v.text.removeRange(sel.min, sel.max), TextRange(sel.min)))
}

/** Puts the copied text in place of the selection. */
fun CanvasViewModel.pasteText() {
    val add = runCatching { systemClipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication())?.toString() }.getOrNull() ?: return
    val v = textField
    val text = v.text.replaceRange(v.selection.min, v.selection.max, add)
    changeTextField(TextFieldValue(text, TextRange(v.selection.min + add.length)))
}

/** Copies a font file into the app and offers it (the core keeps it); the text being edited takes it. */
fun CanvasViewModel.importFonts(uris: List<android.net.Uri>) {
    viewModelScope.launch {
        val families = withContext(Dispatchers.IO) { uris.mapNotNull { importFont(it) } }
        val family = families.firstOrNull() ?: return@launch
        restyleText(TextStyleChange.Font(family))
        showDone(if (families.size > 1) "Fonts imported" else "Font imported")
    }
}

/** The font file at [uri] kept and offered: its family, or null. */
internal fun CanvasViewModel.importFont(uri: android.net.Uri): String? =
    runCatching {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Font.ttf"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        // kept in the fonts folder by the core; a file that isn't a font is dropped
        TextRenderer.importFont(bytes, name)
    }.getOrNull()

/** New text in the middle of the canvas, all of it selected for typing over. */
fun CanvasViewModel.addText() {
    val s = session ?: return
    closePanels()
    val rgb = colorRgb()
    // a medium sans, sized and placed as new text looks on screen (above the keyboard, about
    // a quarter of the way down)
    var info = TextInfo(
        text = "Text", font = "sans-serif", style = "Medium",
        size = 0f,
        kerning = 0f, tracking = 0f, leading = 0f, baseline = 0f, opacity = 1f,
        align = TextAlignment.CENTER, underline = false, outline = false, vertical = false, allCaps = false,
        r = ((rgb shr 16) and 255) / 255f, g = ((rgb shr 8) and 255) / 255f, b = (rgb and 255) / 255f,
        x = 0f, y = 0f, width = 0f, runs = emptyList(),
    )
    info = s.newTextPlaced(info, windowSize.x, windowSize.y, screenDensity)
    if (s.textBegin(null, info) == null) {
        message = noRoom()
        return
    }
    startText(info)
}

/** Edits a text layer's text again. */
fun CanvasViewModel.editText(l: LayerInfo) {
    val s = session ?: return
    val info = s.layerText(l.id) ?: return
    closePanels()
    s.textBegin(l.id, info) ?: return
    startText(info)
}

/** Text layer [id] back in its box, selected but with the keyboard away (undo and redo of it). */
internal fun CanvasViewModel.resumeText(id: ULong): Boolean {
    val s = session ?: return false
    val info = s.layerText(id) ?: return false
    s.textBegin(id, info) ?: return false
    startText(info, keyboard = false)
    return true
}

internal fun CanvasViewModel.startText(info: TextInfo, keyboard: Boolean = true) {
    textKeyboard = keyboard
    textInfo = info
    textField = TextFieldValue(info.text, TextRange(0, info.text.length))
    textStyleOpen = false
    refreshTextLook()
    renderText()
    refresh()
}

/**
 * Letters chosen by [taps] quick taps in a row at window point [at] on the text being edited:
 * one puts the caret at the place nearest it, two choose the word there, three all of it. The
 * field's too, so what is typed goes there.
 */
fun CanvasViewModel.tapText(at: Offset, taps: Int) {
    val p = windowToCanvas(at) ?: return
    val chosen = session?.textTap(p.x, p.y, taps.coerceAtLeast(1).toUInt()) ?: return
    val text = textField.text
    val (start, end) = chosen.map { textIndexToUtf16(text, it).toInt() }.takeIf { it.size == 2 } ?: return
    changeTextField(TextFieldValue(text, TextRange(start, end)))
}

fun CanvasViewModel.changeTextField(v: TextFieldValue) {
    val before = textField
    textField = v
    // what was typed is found against where the letters chosen were; then where they now are
    if (v.text != textInfo?.text) changeText { it.copy(text = v.text) }
    if (v.text != before.text || v.selection != before.selection) {
        val (start, end) = textChosen(v)
        session?.textSelect(start, end)
        refreshTextLook()
    }
}

fun CanvasViewModel.changeText(f: (TextInfo) -> TextInfo) {
    val info = textInfo?.let(f) ?: return
    textInfo = session?.setText(info) ?: info
    renderText()
}

/**
 * Draws the text into its layer off the main thread (only where it was and now reaches), one
 * drawing at a time: those asked for while one draws are passed over for the latest.
 */
internal fun CanvasViewModel.renderText() {
    val s = session ?: return
    if (textInfo == null) return
    val generation = ++textGeneration
    val before = textJob
    textJob = viewModelScope.launch {
        before?.join()
        if (generation == textGeneration) withContext(Dispatchers.Default) { TextRenderer.drawInto(s) }
    }
}

/** Ends editing text: [keep] makes it one undo step, otherwise it goes back. */
fun CanvasViewModel.endText(keep: Boolean) {
    val s = session ?: return
    if (textInfo == null) return
    val job = textJob
    textInfo = null
    textStyleOpen = false
    viewModelScope.launch {
        // the last drawing lands first
        job?.join()
        s.textEnd(keep)
        refresh()
        if (layersShown) refreshThumbnails()
    }
}

/** Moves the text being edited by a drag from window point [from] to [to]. */
fun CanvasViewModel.moveText(from: Offset, to: Offset) {
    val a = windowToCanvas(from) ?: return
    val b = windowToCanvas(to) ?: return
    changeText { it.copy(x = it.x + b.x - a.x, y = it.y + b.y - a.y) }
}

/** The side of the text box whose dot is within [reach] pixels of window point [p], if any. */
fun CanvasViewModel.textSideAt(p: Offset, reach: Float): TextSide? = session?.textSideAt(p.x, p.y, reach)

/** A drag of the text box's [side] begins at window point [from]: the side follows it, the other staying put. */
fun CanvasViewModel.beginTextSide(side: TextSide, from: Offset): Boolean = session?.textSideBegin(side, from.x, from.y) == true

/** The side being dragged follows the drag to window point [to]: the text wraps to the box's new width. */
fun CanvasViewModel.dragTextSide(to: Offset) {
    val info = session?.textSideDrag(to.x, to.y) ?: return
    textInfo = info
    renderText()
}

/** The side's drag ends: one undo step while the text is edited. */
fun CanvasViewModel.endTextSide() {
    session?.textSideEnd()
    refresh()
}

/**
 * Inside editing text, a change to its box's width undone ([undo]) or redone, the text still
 * edited; false if there is none.
 */
internal fun CanvasViewModel.stepTextBox(undo: Boolean): Boolean {
    val s = session ?: return false
    val name = (if (undo) s.textBoxUndo() else s.textBoxRedo()) ?: return false
    s.editingText()?.let(s::layerText)?.let { textInfo = it }
    renderText()
    s.stepNotice(undo, name)?.let { showNotice(it) }
    refresh()
    return true
}

/** Whether the current layer holds editable text. */
fun CanvasViewModel.currentIsText() = layers.firstOrNull { it.current }?.text == true

/**
 * Whether the current layer takes a change (a stroke, a transform, an adjustment, clearing,
 * cutting); if not (locked, hidden, or an empty group), a dialog says why. What was tried is
 * not done afterwards. With [paint] (a stroke or a fill) a group current says so too; an
 * adjustment or a [copy] (or a cut, [cut]) takes one layer, so a group current or several
 * layers chosen say so.
 */
fun CanvasViewModel.currentTakesChanges(paint: Boolean = false, adjustment: Adjustment? = null, copy: Boolean = false, cut: Boolean = false): Boolean {
    val current = layers.firstOrNull { it.current } ?: return true
    // the shared core decides
    val asked = when {
        paint -> session?.whyUnpainted()
        adjustment != null -> session?.whyNotAdjusted(adjustment)
        copy || cut -> session?.whyNotCopied(cut)
        else -> session?.whyBlocked()
    }
    // Liquify's alert is about the layer chosen whose alpha is locked
    val l = if (asked == io.github.artcraftandroid.procraft.core.Blocked.ALPHA_LOCKED) {
        session?.alphaLockedChosen()?.let { id -> layers.firstOrNull { it.id == id } } ?: current
    } else {
        current
    }
    val why = when (asked) {
        io.github.artcraftandroid.procraft.core.Blocked.LOCKED -> Blocked.LOCKED
        io.github.artcraftandroid.procraft.core.Blocked.HIDDEN -> Blocked.HIDDEN
        io.github.artcraftandroid.procraft.core.Blocked.EMPTY_GROUP -> Blocked.EMPTY_GROUP
        io.github.artcraftandroid.procraft.core.Blocked.MESH_HIDDEN -> Blocked.MESH_HIDDEN
        io.github.artcraftandroid.procraft.core.Blocked.MESH_LOCKED -> Blocked.MESH_LOCKED
        io.github.artcraftandroid.procraft.core.Blocked.GROUP_HIDDEN -> Blocked.GROUP_HIDDEN
        io.github.artcraftandroid.procraft.core.Blocked.GROUP -> Blocked.GROUP
        io.github.artcraftandroid.procraft.core.Blocked.SEVERAL -> Blocked.SEVERAL
        io.github.artcraftandroid.procraft.core.Blocked.ALPHA_LOCKED -> Blocked.ALPHA_LOCKED
        null -> return true
    }
    blockedBy = session?.hiddenBy()
    blocked = why to l
    return false
}

/** The dialog's answer: unlock or show the layer ([fix]), open Layers ([openLayers]), or neither. */
fun CanvasViewModel.answerBlocked(fix: Boolean, openLayers: Boolean) {
    val (why, l) = blocked ?: return
    blocked = null
    if (fix) {
        when (why) {
            Blocked.LOCKED -> toggleLocked(l)
            Blocked.ALPHA_LOCKED -> if (l.alphaLock) toggleAlphaLock(l)
            // its own box ticked (what else hides it stays)
            Blocked.HIDDEN, Blocked.GROUP_HIDDEN -> {
                var said: String? = null
                layerChange { said = it.showLayer(l.id) }
                said?.let { showDone(it) }
            }
            Blocked.EMPTY_GROUP, Blocked.GROUP, Blocked.SEVERAL -> Unit
            Blocked.MESH_HIDDEN, Blocked.MESH_LOCKED -> {
                session?.freeMeantMeshes()
                readMeshes()
            }
        }
    }
    if (openLayers && floatingWindow(io.github.artcraftandroid.procraft.core.FloatingPanel.LAYERS) == null) panel = Panel.LAYERS
}

fun CanvasViewModel.rasterize(l: LayerInfo) {
    layerChange { it.rasterizeLayer(l.id) }
    showDone(if (l.text) "Text rasterized" else "Layer rasterized", warning = true)
}

/**
 * A plugin's layer [l]: the strokes the selection takes (every one with nothing selected) given
 * the paint colour ([colour]), or the paint brush with its size and opacity, as strokes.
 */
fun CanvasViewModel.restyleStrokes(l: LayerInfo, colour: Boolean) = layerChange {
    it.selectLayer(l.id)
    it.restylePluginItems(colour, !colour, !colour, !colour)
}

