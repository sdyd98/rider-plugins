package model

import com.jetbrains.rd.generator.nova.Ext
import com.jetbrains.rd.generator.nova.PredefinedType
import com.jetbrains.rd.generator.nova.call
import com.jetbrains.rd.generator.nova.field
import com.jetbrains.rd.generator.nova.immutableList
import com.jetbrains.rider.model.nova.ide.SolutionModel

/**
 * What the plugin may ask the ReSharper backend, and what it gets back.
 *
 * Deliberately one question. The plugin's rule is that it reports only facts that are 100% exact and
 * makes no judgements; a call that returns "the functions this file declares, and where they are" is
 * exactly that shape, and anything richer would start smuggling interpretation into the tool side.
 *
 * The AI still decides what those functions mean. This only replaces the part that was never a
 * judgement in the first place — finding them.
 */
object CodemapModel : Ext(SolutionModel.Solution) {

    /** One declaration, as the C++ engine sees it. */
    private val CppFunction = structdef {
        /** The declarator text, single-line — what a human would recognise as the signature. */
        field("signature", PredefinedType.string)

        /** Byte offset of the declaration's first character in the file. */
        field("offset", PredefinedType.int)

        /** 1-based line of that offset, so the frontend need not re-read the file to navigate. */
        field("line", PredefinedType.int)

        /** True for a definition (it has a body), false for a bare declaration. */
        field("definition", PredefinedType.bool)
    }

    init {
        /**
         * Every function declaration in one file, by solution-relative path.
         *
         * Empty means "nothing found", which is not the same as "not indexed yet" — the backend waits
         * for the C++ caches rather than answering early, so an empty list is an answer.
         */
        call("functionsIn", PredefinedType.string, immutableList(CppFunction))
    }
}
