using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using JetBrains.Rider.Model;
using JetBrains.Application.Parts;
using JetBrains.Application.Threading;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.Rd.Tasks;
using JetBrains.ReSharper.Feature.Services.Protocol;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Cpp.Language;
using JetBrains.ReSharper.Psi.Cpp.Tree;
using JetBrains.ReSharper.Psi.Files;
using JetBrains.ReSharper.Psi.Tree;

namespace Codemap.Backend
{
    /// <summary>
    /// Answers the plugin's one question: which functions does this file declare, and where.
    /// </summary>
    /// <remarks>
    /// This is the whole reason the backend exists. Function locations used to be a text match against a
    /// signature an AI had copied into a note; when the signature drifted, the function went grey. C++
    /// semantics live only in this process, so this is where the question can actually be answered.
    ///
    /// It answers with facts and nothing else — names, offsets, lines, and whether there is a body. What
    /// any of it *means* stays the AI's job, which is the same rule the MCP tools follow.
    /// </remarks>
    [SolutionComponent(Instantiation.ContainerAsyncAnyThreadSafe)]
    public class CodemapHost
    {
        private readonly ISolution _solution;

        public CodemapHost(ISolution solution, IShellLocks locks)
        {
            _solution = solution;
            // Set on the model rather than polling anything: the plugin asks when it wants an answer,
            // which is also what lets the reply be current instead of a snapshot taken at startup.
            solution.GetProtocolSolution().GetCodemapModel().FunctionsIn.Set(
                (lifetime, path) =>
                {
                    WaitForCaches(lifetime);
                    var result = locks.ExecuteWithReadLock(() => Functions(path));
                    return RdTask<List<CppFunction>>.Successful(result);
                });
        }

        /// <summary>
        /// Blocks until the PSI caches have settled, or until the request is cancelled.
        /// </summary>
        /// <remarks>
        /// Without this, a question asked while the solution is still indexing gets an empty list, and an
        /// empty list is indistinguishable from "this file declares nothing" — the caller would quietly
        /// fall back to the note's own anchors and never learn it asked too early. Waiting turns a wrong
        /// answer into a slow one.
        ///
        /// The caller is a background thread on the plugin side, so blocking here costs nothing that a
        /// person can see. The lifetime is the request's: closing the solution ends the wait.
        /// </remarks>
        private void WaitForCaches(Lifetime lifetime)
        {
            var state = _solution.GetPsiServices().CachesState;
            var deadline = DateTime.UtcNow.AddSeconds(60);
            while (!state.IsIdle.Value && lifetime.IsAlive && DateTime.UtcNow < deadline)
                Thread.Sleep(200);
        }

        /// <summary>
        /// Every function declaration in <paramref name="path"/>, which is relative to the solution
        /// directory — the same shape the notes are keyed by.
        /// </summary>
        private List<CppFunction> Functions(string path)
        {
            var found = new List<CppFunction>();
            var sourceFile = Find(path);
            if (sourceFile == null) return found;

            foreach (var psiFile in sourceFile.GetPsiFiles(CppLanguage.Instance))
            {
                var document = sourceFile.Document;
                foreach (var declaration in psiFile.Descendants<SimpleDeclaration>().ToEnumerable())
                {
                    if (!IsFunction(declaration)) continue;

                    var offset = declaration.GetDocumentRange().TextRange.StartOffset;
                    var signature = (declaration.DeclarationNode?.GetText() ?? declaration.GetText())
                        .Split('\n')[0].Trim();

                    found.Add(new CppFunction(
                        signature,
                        offset,
                        document != null ? (int)document.GetCoordsByOffset(offset).Line.Plus1() : 0,
                        HasBody(declaration)));
                }
            }
            return found;
        }

        /// <summary>
        /// Whether this declaration declares a function.
        /// </summary>
        /// <remarks>
        /// Two tests, because there is no single node type for a C++ function. Building a
        /// <see cref="CppFunctionDeclaration"/> only succeeds for a definition, so every declaration in a
        /// header would answer "no" — those are recognised by carrying a parameter list of their own.
        /// "Of their own" matters: a class declaration contains all its methods' parameter lists, and a
        /// plain descendant test would call the class a function.
        /// </remarks>
        private static bool IsFunction(SimpleDeclaration declaration) =>
            CppFunctionDeclaration.TryCreateFromFunctionDeclaration(declaration) != null ||
            declaration.Descendants<FunctionParameters>().ToEnumerable()
                .Any(p => p.GetContainingNode<SimpleDeclaration>(true) == declaration);

        /// <summary>
        /// Whether this declaration is a definition — whether it carries a body.
        /// </summary>
        /// <remarks>
        /// Read off the tree, not from the resolve entity. Asking the resolver this early answers "no" for
        /// everything, which is worse than not answering: a definition reported as a declaration looks
        /// like a fact. The body is right there in the syntax.
        /// </remarks>
        private static bool HasBody(SimpleDeclaration declaration) =>
            declaration.CompoundStatementNode != null ||
            declaration.FunctionTryBlockNode != null ||
            declaration.ConstructorBlock != null;

        /// <summary>Matches by path suffix — the plugin knows solution-relative paths, the model knows absolute ones.</summary>
        private IPsiSourceFile Find(string path)
        {
            var wanted = path.Replace('\\', '/');
            return _solution.GetAllProjects()
                .SelectMany(p => p.GetAllProjectFiles())
                .Select(f => f.ToSourceFile())
                .FirstOrDefault(f => f != null &&
                                     f.GetLocation().FullPath.Replace('\\', '/').EndsWith(wanted, StringComparison.OrdinalIgnoreCase));
        }
    }
}
