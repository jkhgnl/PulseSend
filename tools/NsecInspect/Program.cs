// See https://aka.ms/new-console-template for more information
using System.Reflection;
using NSec.Cryptography;

var asm = typeof(Key).Assembly;

void Print(string title, IEnumerable<MethodInfo> methods)
{
    Console.WriteLine(title);
    foreach (var method in methods.OrderBy(m => m.Name))
    {
        Console.WriteLine(method);
    }
    Console.WriteLine();
}

var shared = asm.GetType("NSec.Cryptography.SharedSecret");
Print("SharedSecret methods:", shared!.GetMethods(BindingFlags.Public | BindingFlags.Instance));

var keyType = asm.GetType("NSec.Cryptography.Key");
Print("Key Import overloads:", keyType!.GetMethods(BindingFlags.Public | BindingFlags.Static)
    .Where(m => m.Name == "Import"));

var kdf = asm.GetType("NSec.Cryptography.KeyDerivationAlgorithm");
Print("KeyDerivationAlgorithm DeriveKey overloads:", kdf!.GetMethods(BindingFlags.Public | BindingFlags.Instance)
    .Where(m => m.Name == "DeriveKey"));
