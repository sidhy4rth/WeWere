import Foundation
import Security

/// Invite codes are short enough to read aloud but drawn from a cryptographic RNG,
/// so they cannot be walked or guessed in sequence.
///
/// The alphabet omits I, O, S and the digits 0, 1, 5 — the characters people confuse
/// when copying a code off someone else's screen. That leaves 30 symbols; over six
/// places that is 7.3e8 codes, and a wrong guess only ever resolves to a group that
/// does not exist.
enum InviteCodes {

    private static let alphabet = Array("ABCDEFGHJKLMNPQRTUVWXYZ2346789")

    static func generate(length: Int = Limits.inviteCodeLength) -> String {
        var out = ""
        for _ in 0..<length {
            var byte: UInt32 = 0
            _ = withUnsafeMutableBytes(of: &byte) { SecRandomCopyBytes(kSecRandomDefault, 4, $0.baseAddress!) }
            out.append(alphabet[Int(byte % UInt32(alphabet.count))])
        }
        return out
    }

    /// Normalises what a user typed: case and the separators people add by habit.
    static func normalise(_ input: String) -> String {
        String(input.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            .filter { $0 != " " && $0 != "-" && $0 != "_" })
    }

    static func isPlausible(_ code: String) -> Bool {
        code.count == Limits.inviteCodeLength && code.allSatisfy { alphabet.contains($0) }
    }

    /// Extracts the code from `https://wewere.vercel.app/join/GA7X2M` or `roll://join/GA7X2M`.
    static func fromLink(_ link: String) -> String? {
        var trimmed = link.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        let candidate = trimmed.split(separator: "/").last.map(String.init) ?? ""
        let normalised = normalise(candidate)
        return isPlausible(normalised) ? normalised : nil
    }
}
