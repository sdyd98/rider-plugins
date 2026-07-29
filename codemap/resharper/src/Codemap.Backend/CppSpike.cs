using System;
using System.Linq;
using System.Text;
using System.Threading;
using JetBrains.Application.Parts;
using JetBrains.Application.Threading;
using JetBrains.ProjectModel;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Cpp.Language;
using JetBrains.ReSharper.Psi.Cpp.Tree;
using JetBrains.ReSharper.Psi.Files;
using JetBrains.ReSharper.Psi.Tree;

namespace Codemap.Backend
{
    /// <summary>
    /// SPIKE — asks ReSharper C++ for the functions it found in a file, and writes what came back.
    /// </summary>
    /// <remarks>
    /// The whole reason for a backend: the JVM side has no C++ symbols, so every function location has so
    /// far been a text match against a signature the AI copied. This answers the only question that decides
    /// whether the backend is worth building — can we get real declarations, with real names and real
    /// offsets, out of the engine that actually parsed the code.
    ///
    /// Polls rather than subscribing to cache readiness: a spike wants a yes or no, and the readiness
    /// protocol is one more unknown to get wrong on the way to it.
    /// </remarks>
    [SolutionComponent(Instantiation.ContainerAsyncAnyThreadSafe)]
    public class CppSpike
    {
        public CppSpike(ISolution solution, IShellLocks locks)
        {
            var thread = new Thread(() => Run(solution, locks)) { IsBackground = true, Name = "codemap-spike" };
            thread.Start();
        }

        private static void Run(ISolution solution, IShellLocks locks)
        {
            for (var attempt = 1; attempt <= 30; attempt++)
            {
                Thread.Sleep(2000);
                try
                {
                    var report = locks.ExecuteWithReadLock(() => Inspect(solution));
                    if (report != null)
                    {
                        Probe.Note(report);
                        return;
                    }
                }
                catch (Exception e)
                {
                    Probe.Note($"spike attempt {attempt} threw {e.GetType().Name}: {e.Message}");
                    return;
                }
            }
            Probe.Note("spike gave up: no C++ file with declarations after 60s");
        }

        /// <summary>Returns null while nothing is indexed yet, so the caller can keep waiting.</summary>
        private static string Inspect(ISolution solution)
        {
            var sourceFiles = solution.GetAllProjects()
                .SelectMany(p => p.GetAllProjectFiles())
                .Select(f => f.ToSourceFile())
                .Where(f => f != null)
                .ToList();

            if (sourceFiles.Count == 0) return null;

            var text = new StringBuilder();
            text.AppendLine($"spike: {sourceFiles.Count} source files");

            var found = 0;
            var anyPsi = false;
            foreach (var sourceFile in sourceFiles)
            {
                var psiFiles = sourceFile.GetPsiFiles(CppLanguage.Instance).ToList();
                // Reported per file, because "no functions in this header" and "no PSI for this header at
                // all" are different problems and only one of them is about node types.
                text.AppendLine($"  [{sourceFile.Name}] psi files: {psiFiles.Count}");

                foreach (var psiFile in psiFiles)
                {
                    var kinds = psiFile.Descendants<ITreeNode>().ToEnumerable()
                        .Where(n => n.GetType().Name.Contains("Declar") || n.GetType().Name.Contains("Member"))
                        .GroupBy(n => n.GetType().Name)
                        .OrderByDescending(g => g.Count())
                        .Select(g => $"{g.Key}×{g.Count()}");
                    text.AppendLine($"    노드: {string.Join(", ", kinds)}");

                    // A function is not its own node type here: every declaration is a SimpleDeclaration,
                    // and asking CppFunctionDeclaration to build itself from one is what tells you whether
                    // that particular declaration is a function.
                    foreach (var declaration in psiFile.Descendants<SimpleDeclaration>().ToEnumerable())
                    {
                        // Two kinds, and they need two tests. TryCreate… only accepts a definition — every
                        // declaration in a header returns null from it — so a bodiless declaration is
                        // recognised by having a parameter list at all, which is what makes a declarator
                        // a function declarator.
                        var function = CppFunctionDeclaration.TryCreateFromFunctionDeclaration(declaration);
                        // Its own parameter list, not one belonging to a member: a class declaration
                        // contains every method's parameters, and a plain descendant test calls the class
                        // itself a function.
                        var parameters = declaration.Descendants<FunctionParameters>().ToEnumerable()
                            .Any(p => p.GetContainingNode<SimpleDeclaration>(true) == declaration);
                        if (function == null && !parameters) continue;

                        found++;
                        // The declarator text, first line only: enough for a spike to show that what came
                        // back is a real signature and not a guess.
                        var name = (declaration.DeclarationNode?.GetText() ?? declaration.GetText())
                            .Split('\n')[0].Trim();
                        if (name.Length > 90) name = name.Substring(0, 90) + "…";
                        var range = declaration.GetDocumentRange();
                        var body = function?.GetFunctionResolveEntity()?.HasBody == true ? "정의" : "선언";
                        text.AppendLine($"    → {name} @ {range.TextRange.StartOffset} ({body})");
                    }
                }

                if (psiFiles.Count > 0) anyPsi = true;
            }

            // Waiting on PSI, not on functions: a header that legitimately has none would otherwise keep
            // the spike polling until it gave up, and report nothing about why.
            return anyPsi ? text.ToString() : null;
        }
    }
}
