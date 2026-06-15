package io.github.muindor.tcresearchsolver.solver

// ---------------------------------------------------------------------------
// CellState — sealed class (spec §2.2)
// ---------------------------------------------------------------------------

sealed class CellState {
    object Dead : CellState()
    data class Anchor(val aspect: Aspect) : CellState()
    object Empty : CellState()
    data class Placed(val aspect: Aspect, val locked: Boolean) : CellState()
}

// ---------------------------------------------------------------------------
// Board — radius + LinkedHashMap<String, CellState> (absent key == Empty)
// ---------------------------------------------------------------------------

class Board(val radius: Int, val cells: LinkedHashMap<String, CellState>)

fun createBoard(radius: Int): Board {
    if (radius < 2 || radius > 5) throw IllegalArgumentException("radius must be an integer 2..5, got $radius")
    return Board(radius, LinkedHashMap())
}

fun getState(board: Board, h: Hex): CellState {
    if (!isOnBoard(h, board.radius)) throw IllegalArgumentException("hex ${hexKey(h)} is off board (R=${board.radius})")
    return board.cells[hexKey(h)] ?: CellState.Empty
}

fun setState(board: Board, h: Hex, s: CellState) {
    if (!isOnBoard(h, board.radius)) throw IllegalArgumentException("hex ${hexKey(h)} is off board (R=${board.radius})")
    val key = hexKey(h)
    if (s is CellState.Empty) board.cells.remove(key)
    else board.cells[key] = s
}

// ---------------------------------------------------------------------------
// FilledCell — analogous to the TS interface
// ---------------------------------------------------------------------------

data class FilledCell(val hex: Hex, val aspect: Aspect, val locked: Boolean, val isAnchor: Boolean)

fun filledCells(board: Board): List<FilledCell> {
    val out = ArrayList<FilledCell>()
    for (h in boardCells(board.radius)) {
        val s = getState(board, h)
        when (s) {
            is CellState.Anchor -> out.add(FilledCell(h, s.aspect, false, true))
            is CellState.Placed -> out.add(FilledCell(h, s.aspect, s.locked, false))
            else -> {}
        }
    }
    return out
}

fun anchorCells(board: Board): List<Pair<Hex, Aspect>> =
    filledCells(board).filter { it.isAnchor }.map { Pair(it.hex, it.aspect) }

/** On-board neighbors that are filled (ANCHOR or PLACED). */
fun filledNeighbors(board: Board, h: Hex): List<FilledCell> {
    val out = ArrayList<FilledCell>()
    for (n in neighborsOf(h)) {
        if (!isOnBoard(n, board.radius)) continue
        val s = getState(board, n)
        when (s) {
            is CellState.Anchor -> out.add(FilledCell(n, s.aspect, false, true))
            is CellState.Placed -> out.add(FilledCell(n, s.aspect, s.locked, false))
            else -> {}
        }
    }
    return out
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

enum class ValidationErrorType {
    INVALID_LINK, SAME_ASPECT_ADJACENT, ANCHORS_DISCONNECTED, PLACED_ON_DEAD, MALFORMED
}

data class ValidationError(val type: ValidationErrorType, val cells: List<Hex>)
data class ValidationResult(val valid: Boolean, val errors: List<ValidationError>)

fun validate(data: AspectData, board: Board): ValidationResult {
    val errors = ArrayList<ValidationError>()
    val filled = filledCells(board)
    val filledKeys = LinkedHashSet(filled.map { hexKey(it.hex) })
    val aspectAt = LinkedHashMap<String, Aspect>().apply { for (c in filled) put(hexKey(c.hex), c.aspect) }

    // 1) pairwise adjacency validity (each undirected pair once)
    for (c in filled) {
        for (n in neighborsOf(c.hex)) {
            val nk = hexKey(n)
            if (!filledKeys.contains(nk)) continue
            if (hexKey(c.hex) >= nk) continue  // dedupe ordered pair (G6)
            val a = c.aspect
            val b = aspectAt[nk]!!
            if (a == b) errors.add(ValidationError(ValidationErrorType.SAME_ASPECT_ADJACENT, listOf(c.hex, n)))
            else if (!isValidLink(data, a, b)) errors.add(ValidationError(ValidationErrorType.INVALID_LINK, listOf(c.hex, n)))
        }
    }

    // 2) anchors connectivity (>=2 anchors must share one filled component)
    if (!anchorsConnectedInternal(board)) {
        errors.add(ValidationError(ValidationErrorType.ANCHORS_DISCONNECTED, anchorCells(board).map { it.first }))
    }

    return ValidationResult(errors.isEmpty(), errors)
}

private fun anchorsConnectedInternal(board: Board): Boolean {
    val anchors = anchorCells(board)
    if (anchors.size <= 1) return true
    val filled = LinkedHashSet(filledCells(board).map { hexKey(it.hex) })
    // BFS from first anchor over filled adjacency
    val start = hexKey(anchors[0].first)
    val seen = LinkedHashSet<String>()
    seen.add(start)
    val queue = ArrayDeque<Hex>()
    queue.add(anchors[0].first)
    while (queue.isNotEmpty()) {
        val cur = queue.removeLast()
        for (n in neighborsOf(cur)) {
            val nk = hexKey(n)
            if (filled.contains(nk) && seen.add(nk)) {
                queue.add(n)
            }
        }
    }
    return anchors.all { seen.contains(hexKey(it.first)) }
}

fun allAnchorsConnected(board: Board): Boolean = anchorsConnectedInternal(board)

/** A finished solution: fully valid AND all anchors in one component. */
fun isComplete(data: AspectData, board: Board): Boolean = validate(data, board).valid

// ---------------------------------------------------------------------------
// Serialization
// ---------------------------------------------------------------------------

const val BOARD_SCHEMA_VERSION = 1

data class SerializedCell(val coord: String, val state: String, val aspect: Aspect? = null, val locked: Boolean? = null)
data class SerializedBoard(val schemaVersion: Int, val radius: Int, val cells: List<SerializedCell>)

fun serializeBoard(board: Board): SerializedBoard {
    val cells = ArrayList<SerializedCell>()
    for ((key, s) in board.cells) {
        when (s) {
            is CellState.Empty -> continue
            is CellState.Dead -> cells.add(SerializedCell(key, "DEAD"))
            is CellState.Anchor -> cells.add(SerializedCell(key, "ANCHOR", s.aspect))
            is CellState.Placed -> cells.add(SerializedCell(key, "PLACED", s.aspect, s.locked))
        }
    }
    return SerializedBoard(BOARD_SCHEMA_VERSION, board.radius, cells)
}

/** Overload: accept a typed DTO directly, mirroring the TS round-trip where
 *  serializeBoard's plain-object output is passable to deserializeBoard. */
fun deserializeBoard(data: AspectData, dto: SerializedBoard): Board {
    if (dto.schemaVersion != BOARD_SCHEMA_VERSION)
        throw IllegalArgumentException("board: unsupported schemaVersion ${dto.schemaVersion}")
    val board = createBoard(dto.radius)
    for (c in dto.cells) {
        val hex = parseHexKey(c.coord)
        if (!isOnBoard(hex, board.radius)) throw IllegalArgumentException("board: coord ${c.coord} off radius ${board.radius}")
        fun checkAspect(a: Aspect?): Aspect {
            if (a == null || !data.universe.contains(a)) throw IllegalArgumentException("board: unknown aspect '${a}'")
            return a
        }
        when (c.state) {
            "DEAD" -> setState(board, hex, CellState.Dead)
            "ANCHOR" -> setState(board, hex, CellState.Anchor(checkAspect(c.aspect)))
            "PLACED" -> setState(board, hex, CellState.Placed(checkAspect(c.aspect), c.locked == true))
            else -> throw IllegalArgumentException("board: bad cell.state '${c.state}'")
        }
    }
    return board
}

fun deserializeBoard(data: AspectData, raw: Any?): Board {
    if (raw is SerializedBoard) return deserializeBoard(data, raw)
    if (raw == null || raw !is Map<*, *>) throw IllegalArgumentException("board: not an object")
    val obj = raw as Map<*, *>
    val radiusRaw = obj["radius"]
    val radius = when (radiusRaw) {
        is Int -> radiusRaw
        is Long -> radiusRaw.toInt()
        is Double -> if (radiusRaw == radiusRaw.toLong().toDouble()) radiusRaw.toInt() else throw IllegalArgumentException("board: bad radius $radiusRaw")
        else -> throw IllegalArgumentException("board: bad radius $radiusRaw")
    }
    if (radius < 2 || radius > 5) throw IllegalArgumentException("board: bad radius $radius")

    val ver = obj["schemaVersion"]
    val verInt = when (ver) {
        is Int -> ver
        is Long -> ver.toInt()
        is Double -> if (ver == ver.toLong().toDouble()) ver.toInt() else throw IllegalArgumentException("board: unsupported schemaVersion $ver")
        else -> throw IllegalArgumentException("board: unsupported schemaVersion $ver")
    }
    if (verInt != BOARD_SCHEMA_VERSION) throw IllegalArgumentException("board: unsupported schemaVersion $verInt")

    val cellsRaw = obj["cells"] ?: throw IllegalArgumentException("board: cells must be an array")
    if (cellsRaw !is List<*>) throw IllegalArgumentException("board: cells must be an array")

    val board = createBoard(radius)
    for (cellRaw in cellsRaw) {
        if (cellRaw == null || cellRaw !is Map<*, *>) throw IllegalArgumentException("board: bad cell")
        val cell = cellRaw as Map<*, *>
        val coordRaw = cell["coord"]
        if (coordRaw !is String) throw IllegalArgumentException("board: cell.coord must be a string")
        val hex = parseHexKey(coordRaw)
        if (!isOnBoard(hex, board.radius)) throw IllegalArgumentException("board: coord $coordRaw off radius ${board.radius}")

        fun checkAspect(a: Any?): Aspect {
            if (a !is String || !data.universe.contains(a)) throw IllegalArgumentException("board: unknown aspect '${a}'")
            return a
        }

        when (val stateRaw = cell["state"]) {
            "DEAD" -> setState(board, hex, CellState.Dead)
            "ANCHOR" -> setState(board, hex, CellState.Anchor(checkAspect(cell["aspect"])))
            "PLACED" -> setState(board, hex, CellState.Placed(checkAspect(cell["aspect"]), cell["locked"] == true))
            else -> throw IllegalArgumentException("board: bad cell.state '$stateRaw'")
        }
    }
    return board
}
