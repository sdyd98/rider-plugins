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
            foreach (var sourceFile in sourceFiles)
            {
                foreach (var psiFile in sourceFile.GetPsiFiles(CppLanguage.Instance))
                {
                    // A function is not its own node type here: every declaration is a SimpleDeclaration,
                    // and asking CppFunctionDeclaration to build itself from one is what tells you whether
                    // that particular declaration is a function.
                    foreach (var declaration in psiFile.Descendants<SimpleDeclaration>().ToEnumerable())
                    {
                        var function = CppFunctionDeclaration.TryCreateFromFunctionDeclaration(declaration);
                        if (function == null) continue;

                        found++;
                        // The declarator text, first line only: enough for a spike to show that what came
                        // back is a real signature and not a guess.
                        var name = (declaration.DeclarationNode?.GetText() ?? declaration.GetText())
                            .Split('\n')[0].Trim();
                        if (name.Length > 90) name = name.Substring(0, 90) + "…";
                        var range = declaration.GetDocumentRange();
                        var body = function.GetFunctionResolveEntity()?.HasBody == true ? "정의" : "선언";
                        text.AppendLine($"  {sourceFile.Name}: {name} @ {range.TextRange.StartOffset} ({body})");
                    }
                }
            }

            return found == 0 ? null : text.ToString();
        }
    }
}
