#!/usr/bin/env node
// Prototype renderer: Codama IDL (JSON, rootNode) -> Kotlin client for web3-solana.
// Usage: node renderer/render.mjs <idl.json> <outDir> <kotlin.package>
import { readFileSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';

const [idlPath, outDir, basePackage] = process.argv.slice(2);
if (!idlPath || !outDir || !basePackage) {
  console.error('usage: node renderer/render.mjs <idl.json> <outDir> <kotlin.package>');
  process.exit(1);
}

const root = JSON.parse(readFileSync(idlPath, 'utf8'));
if (root.kind !== 'rootNode') throw new Error('expected a Codama rootNode');
const program = root.program;
const unsupported = [];

// ---------- naming ----------
const KOTLIN_KEYWORDS = new Set(['as', 'break', 'class', 'continue', 'do', 'else', 'false', 'for', 'fun', 'if', 'in',
  'interface', 'is', 'null', 'object', 'package', 'return', 'super', 'this', 'throw', 'true', 'try', 'typealias',
  'typeof', 'val', 'var', 'when', 'while']);
const pascal = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const camel = (s) => s.charAt(0).toLowerCase() + s.slice(1);
const ident = (s) => (KOTLIN_KEYWORDS.has(s) ? '`' + s + '`' : s);
const screaming = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
const programName = pascal(program.name);
// "squadsMultisigProgram" -> SquadsMultisigProgram, "token" -> TokenProgram (no "ProgramProgram").
const programObject = programName.endsWith('Program') ? programName : `${programName}Program`;

function kdoc(docs, indent = '') {
  const lines = (docs || []).map((d) => d.replace(/\*\//g, '*&#47;'));
  if (!lines.length) return '';
  return `${indent}/**\n${lines.map((l) => `${indent} *${l ? ' ' + l : ''}`).join('\n')}\n${indent} */\n`;
}

// ---------- numbers ----------
const NUM = {
  u8: { kt: 'UByte', size: 1, lit: (n) => `${n}u` },
  u16: { kt: 'UShort', size: 2, lit: (n) => `${n}u` },
  u32: { kt: 'UInt', size: 4, lit: (n) => `${n}u` },
  u64: { kt: 'ULong', size: 8, lit: (n) => `${n}uL` },
  i8: { kt: 'Byte', size: 1, lit: (n) => `${n}` },
  i16: { kt: 'Short', size: 2, lit: (n) => `${n}` },
  i32: { kt: 'Int', size: 4, lit: (n) => `${n}` },
  i64: { kt: 'Long', size: 8, lit: (n) => `${n}L` },
  f32: { kt: 'Float', size: 4, lit: (n) => `${n}f` },
  f64: { kt: 'Double', size: 8, lit: (n) => `${n}` },
};
function num(node) {
  const n = NUM[node.format];
  if (!n) { unsupported.push(`number format ${node.format}`); return NUM.u64; }
  return n;
}
const be = (node) => (node.endian === 'be' ? 'true' : 'false');

// ---------- types ----------
const definedTypes = new Map(program.definedTypes.map((t) => [t.name, t]));
const inlineStructs = []; // { name, struct }

// Enums whose variants carry data become sealed classes; plain ones stay Kotlin enums.
const hasData = (enumType) => enumType.variants.some((v) => v.kind !== 'enumEmptyVariantTypeNode');
const enumSize = (enumType) => enumType.size || { kind: 'numberTypeNode', format: 'u8', endian: 'le' };
const isPlainEnumLink = (type) =>
  type.kind === 'definedTypeLinkNode' && definedTypes.get(type.name).type.kind === 'enumTypeNode' &&
  !hasData(definedTypes.get(type.name).type);

// Fields and arguments with an "omitted" default (Anchor discriminators) are written as constants, never asked for.
const isOmitted = (f) => f.defaultValueStrategy === 'omitted' && f.defaultValue;

function fixedSize(type) {
  switch (type.kind) {
    case 'numberTypeNode': return num(type).size;
    case 'publicKeyTypeNode': return 32;
    case 'booleanTypeNode': return num(type.size).size;
    case 'fixedSizeTypeNode': return type.size;
    case 'definedTypeLinkNode': {
      const t = definedTypes.get(type.name).type;
      if (t.kind === 'enumTypeNode' && !hasData(t)) return num(enumSize(t)).size;
      return fixedSize(t);
    }
    default: unsupported.push(`fixed size of ${type.kind}`); return 0;
  }
}

/** Kotlin type for a Codama type node. `hint` names inline structs. */
function ktType(type, hint) {
  switch (type.kind) {
    case 'numberTypeNode': return num(type).kt;
    case 'publicKeyTypeNode': return 'SolanaPublicKey';
    case 'booleanTypeNode': return 'Boolean';
    case 'stringTypeNode': return 'String';
    case 'bytesTypeNode': return 'ByteArray';
    case 'sizePrefixTypeNode': return ktType(type.type, hint);
    case 'fixedSizeTypeNode': return ktType(type.type, hint);
    case 'optionTypeNode': return `${ktType(type.item, hint)}?`;
    case 'arrayTypeNode': return `List<${ktType(type.item, hint + 'Item')}>`;
    case 'definedTypeLinkNode': return pascal(type.name);
    case 'structTypeNode': {
      const name = pascal(hint);
      if (!inlineStructs.find((s) => s.name === name)) inlineStructs.push({ name, struct: type });
      return name;
    }
    default: unsupported.push(`type ${type.kind}`); return 'Any';
  }
}

let varCounter = 0;
const freshVar = () => `v${varCounter++}`;

/** Kotlin statements that write `expr` (of `type`) into writer `w`. */
function writeStmts(type, expr, hint) {
  switch (type.kind) {
    case 'numberTypeNode': return [`w.${type.format}(${expr}${type.format.match(/^[ui]8$/) ? '' : ', bigEndian = ' + be(type)})`];
    case 'publicKeyTypeNode': return [`w.bytes(${expr}.bytes)`];
    case 'booleanTypeNode': return [`w.${type.size.format}(if (${expr}) ${num(type.size).lit(1)} else ${num(type.size).lit(0)})`];
    case 'stringTypeNode':
      if (type.encoding !== 'utf8') unsupported.push(`string encoding ${type.encoding}`);
      return [`w.bytes(${expr}.encodeToByteArray())`];
    case 'bytesTypeNode': return [`w.bytes(${expr})`];
    case 'sizePrefixTypeNode': {
      const v = freshVar();
      const p = type.prefix;
      let encoded;
      if (type.type.kind === 'bytesTypeNode') encoded = expr;
      else if (type.type.kind === 'stringTypeNode') encoded = `${expr}.encodeToByteArray()`;
      else encoded = `DataWriter().also { w -> ${writeStmts(type.type, expr, hint).join('; ')} }.toByteArray()`;
      return [
        `val ${v} = ${encoded}`,
        `w.${p.format}(${v}.size.to${num(p).kt}()${p.format.match(/^[ui]8$/) ? '' : ', bigEndian = ' + be(p)})`,
        `w.bytes(${v})`,
      ];
    }
    case 'fixedSizeTypeNode': {
      if (type.type.kind !== 'bytesTypeNode') { unsupported.push(`fixed size of ${type.type.kind}`); return []; }
      const v = freshVar();
      return [`val ${v} = ${expr}`, `require(${v}.size == ${type.size}) { "expected ${type.size} bytes" }`, `w.bytes(${v})`];
    }
    case 'optionTypeNode': {
      const p = type.prefix;
      const tagW = (n) => `w.${p.format}(${num(p).lit(n)}${p.format.match(/^[ui]8$/) ? '' : ', bigEndian = ' + be(p)})`;
      const v = freshVar();
      const none = [tagW(0)];
      if (type.fixed) none.push(`w.zeros(${fixedSize(type.item)})`);
      return [
        `val ${v} = ${expr}`,
        `if (${v} == null) { ${none.join('; ')} } else { ${[tagW(1), ...writeStmts(type.item, v, hint)].join('; ')} }`,
      ];
    }
    case 'arrayTypeNode': {
      const v = freshVar();
      const out = [];
      const c = type.count;
      if (c.kind === 'prefixedCountNode') out.push(`w.${c.prefix.format}(${expr}.size.to${num(c.prefix).kt}()${c.prefix.format.match(/^[ui]8$/) ? '' : ', bigEndian = ' + be(c.prefix)})`);
      else if (c.kind === 'fixedCountNode') out.push(`require(${expr}.size == ${c.value}) { "expected ${c.value} items" }`);
      else if (c.kind !== 'remainderCountNode') unsupported.push(`array count ${c.kind}`);
      out.push(`for (${v} in ${expr}) { ${writeStmts(type.item, v, hint + 'Item').join('; ')} }`);
      return out;
    }
    case 'definedTypeLinkNode': {
      const t = definedTypes.get(type.name).type;
      if (isPlainEnumLink(type)) return [`w.${enumSize(t).format}(${expr}.ordinal.to${num(enumSize(t)).kt}())`];
      return [`${expr}.write(w)`];
    }
    case 'structTypeNode': ktType(type, hint); return [`${expr}.write(w)`];
    default: unsupported.push(`write ${type.kind}`); return [`TODO("${type.kind}")`];
  }
}

/** Kotlin expression that reads a value of `type` from reader `r`. */
function readExpr(type, hint) {
  switch (type.kind) {
    case 'numberTypeNode': return `r.${type.format}(${type.format.match(/^[ui]8$/) ? '' : 'bigEndian = ' + be(type)})`;
    case 'publicKeyTypeNode': return 'SolanaPublicKey(r.bytes(32))';
    case 'booleanTypeNode': return `(r.${type.size.format}().toLong() != 0L)`;
    case 'stringTypeNode': return 'r.remaining().decodeToString()';
    case 'bytesTypeNode': return 'r.remaining()';
    case 'sizePrefixTypeNode': {
      const p = type.prefix;
      return `r.bytes(r.${p.format}(${p.format.match(/^[ui]8$/) ? '' : 'bigEndian = ' + be(p)}).toInt())${type.type.kind === 'stringTypeNode' ? '.decodeToString()' : ''}`;
    }
    case 'fixedSizeTypeNode':
      if (type.type.kind !== 'bytesTypeNode') unsupported.push(`fixed size of ${type.type.kind}`);
      return `r.bytes(${type.size})`;
    case 'optionTypeNode': {
      const p = type.prefix;
      const skip = type.fixed ? `r.skip(${fixedSize(type.item)}); ` : '';
      return `(if (r.${p.format}(${p.format.match(/^[ui]8$/) ? '' : 'bigEndian = ' + be(p)}).toLong() == 0L) { ${skip}null } else ${readExpr(type.item, hint)})`;
    }
    case 'arrayTypeNode': {
      const c = type.count;
      const item = readExpr(type.item, hint + 'Item');
      if (c.kind === 'fixedCountNode') return `List(${c.value}) { ${item} }`;
      if (c.kind === 'prefixedCountNode') return `List(r.${c.prefix.format}().toInt()) { ${item} }`;
      return `buildList { while (r.hasRemaining()) add(${item}) }`;
    }
    case 'definedTypeLinkNode': {
      const t = definedTypes.get(type.name).type;
      if (isPlainEnumLink(type)) return `${pascal(type.name)}.entries[r.${enumSize(t).format}().toInt()]`;
      return `${pascal(type.name)}.read(r)`;
    }
    case 'structTypeNode': return `${ktType(type, hint)}.read(r)`;
    default: unsupported.push(`read ${type.kind}`); return `TODO("${type.kind}")`;
  }
}

function valueLiteral(value, type) {
  switch (value.kind) {
    case 'numberValueNode': return type.kind === 'numberTypeNode' ? num(type).lit(value.number) : `${value.number}`;
    case 'noneValueNode': return 'null';
    case 'publicKeyValueNode': return `SolanaPublicKey.from("${value.publicKey}")`;
    case 'booleanValueNode': return `${value.boolean}`;
    case 'stringValueNode': return JSON.stringify(value.string);
    case 'bytesValueNode':
      if (value.encoding !== 'base16') { unsupported.push(`bytes value encoding ${value.encoding}`); return null; }
      return `hex("${value.data}")`;
    default: return null;
  }
}

// ---------- struct classes (accounts, defined structs, inline structs) ----------
function structClass(name, struct, docs, extra = '') {
  const fields = struct.fields.map((f) => ({
    name: ident(f.name),
    type: f.type,
    omitted: isOmitted(f) ? valueLiteral(f.defaultValue, f.type) : null,
    kt: isOmitted(f) ? null : ktType(f.type, name + pascal(f.name)),
    docs: f.docs,
  }));
  const kept = fields.filter((f) => f.omitted === null);
  const props = kept.map((f) => `${kdoc(f.docs, '    ')}    val ${f.name}: ${f.kt},`).join('\n');
  const writes = fields
    .flatMap((f) => writeStmts(f.type, f.omitted ?? f.name, name + pascal(f.name)))
    .map((l) => `        ${l}`).join('\n');
  // Fields are read in order into locals; an omitted field (discriminator) must match its constant.
  const reads = fields.map((f, i) => f.omitted !== null
    ? `            require(${readExpr(f.type, name + pascal(f.name))}.contentEquals(${f.omitted})) { "${name}: wrong ${f.name.replace(/`/g, '')}" }`
    : `            val f${i} = ${readExpr(f.type, name + pascal(f.name))}`).join('\n');
  const args = fields.map((f, i) => (f.omitted !== null ? null : `${f.name} = f${i}`)).filter(Boolean).join(', ');
  const constants = fields.filter((f) => f.omitted !== null)
    .map((f) => `        val ${screaming(f.name.replace(/`/g, ''))}: ByteArray get() = ${f.omitted}\n\n`).join('');
  return `${kdoc(docs)}${kept.length ? 'data ' : ''}class ${name}(
${props}
) {
    internal fun write(w: DataWriter) {
${writes}
    }

    fun encode(): ByteArray = DataWriter().also { write(it) }.toByteArray()

    companion object {
${extra}${constants}        internal fun read(r: DataReader): ${name} {
${reads}
            return ${name}(${args})
        }

        fun decode(data: ByteArray): ${name} = read(DataReader(data))
    }
}
`;
}

/** An enum whose variants carry data: a sealed class, tagged by the variant index. */
function sealedEnum(name, enumType, docs) {
  const size = enumSize(enumType);
  const tagW = (i) => `w.${size.format}(${num(size).lit(i)}${size.format.match(/^[ui]8$/) ? '' : ', bigEndian = ' + be(size)})`;
  const variants = enumType.variants.map((v, i) => {
    const vName = pascal(v.name);
    if (v.kind === 'enumEmptyVariantTypeNode') {
      return { decl: `${kdoc(v.docs, '    ')}    data object ${vName} : ${name}()`, write: `is ${vName} -> ${tagW(i)}`, read: `${i} -> ${vName}` };
    }
    if (v.kind !== 'enumStructVariantTypeNode') { unsupported.push(`enum variant ${v.kind} (${name}.${v.name})`); return null; }
    const fields = v.struct.fields.map((f) => ({ name: ident(f.name), type: f.type, kt: ktType(f.type, name + vName + pascal(f.name)), docs: f.docs }));
    const writes = [tagW(i), ...fields.flatMap((f) => writeStmts(f.type, f.name, name + vName + pascal(f.name)))];
    return {
      decl: `${kdoc(v.docs, '    ')}    data class ${vName}(${fields.map((f) => `val ${f.name}: ${f.kt}`).join(', ')}) : ${name}()`,
      write: `is ${vName} -> { ${writes.join('; ')} }`,
      read: `${i} -> ${vName}(${fields.map((f) => `${f.name} = ${readExpr(f.type, name + vName + pascal(f.name))}`).join(', ')})`,
    };
  }).filter(Boolean);
  return `${kdoc(docs)}sealed class ${name} {
${variants.map((v) => v.decl).join('\n')}

    internal fun write(w: DataWriter) {
        when (this) {
${variants.map((v) => `            ${v.write}`).join('\n')}
        }
    }

    fun encode(): ByteArray = DataWriter().also { write(it) }.toByteArray()

    companion object {
        internal fun read(r: DataReader): ${name} = when (val tag = r.${size.format}().toInt()) {
${variants.map((v) => `            ${v.read}`).join('\n')}
            else -> error("${name}: unknown variant $tag")
        }

        fun decode(data: ByteArray): ${name} = read(DataReader(data))
    }
}
`;
}

// ---------- files ----------
const files = new Map();
const pkgDir = (sub) => join(outDir, ...basePackage.split('.'), ...(sub ? [sub] : []));
const header = (sub, imports) => `// Generated by codama-kotlin (prototype) from ${program.name} IDL. Do not edit.
package ${basePackage}${sub ? '.' + sub : ''}

${imports.map((i) => `import ${i}`).join('\n')}
`;

// Program object
files.set(join(pkgDir(''), `${programObject}.kt`), `${header('', ['com.solana.publickey.SolanaPublicKey'])}
${kdoc(program.docs)}object ${programObject} {
    val PROGRAM_ID: SolanaPublicKey = SolanaPublicKey.from("${program.publicKey}")
}
`);

// Binary helpers (pure Kotlin, multiplatform-friendly)
files.set(join(pkgDir(''), 'DataIo.kt'), `${header('', [])}
internal class DataWriter {
    private var buf = ByteArray(64)
    private var size = 0

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(buf, size); size += b.size }
    fun zeros(n: Int) { ensure(n); size += n }

    private fun int(v: Long, width: Int, bigEndian: Boolean) {
        ensure(width)
        for (i in 0 until width) {
            val shift = 8 * (if (bigEndian) width - 1 - i else i)
            buf[size + i] = (v ushr shift).toByte()
        }
        size += width
    }

    fun u8(v: UByte) = int(v.toLong(), 1, false)
    fun i8(v: Byte) = int(v.toLong(), 1, false)
    fun u16(v: UShort, bigEndian: Boolean = false) = int(v.toLong(), 2, bigEndian)
    fun i16(v: Short, bigEndian: Boolean = false) = int(v.toLong(), 2, bigEndian)
    fun u32(v: UInt, bigEndian: Boolean = false) = int(v.toLong(), 4, bigEndian)
    fun i32(v: Int, bigEndian: Boolean = false) = int(v.toLong(), 4, bigEndian)
    fun u64(v: ULong, bigEndian: Boolean = false) = int(v.toLong(), 8, bigEndian)
    fun i64(v: Long, bigEndian: Boolean = false) = int(v, 8, bigEndian)
    fun f32(v: Float, bigEndian: Boolean = false) = int(v.toRawBits().toLong(), 4, bigEndian)
    fun f64(v: Double, bigEndian: Boolean = false) = int(v.toRawBits(), 8, bigEndian)

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

internal class DataReader(private val data: ByteArray, private var offset: Int = 0) {
    fun hasRemaining() = offset < data.size
    fun skip(n: Int) { require(offset + n <= data.size) { "unexpected end of data" }; offset += n }
    fun bytes(n: Int): ByteArray { require(offset + n <= data.size) { "unexpected end of data" }; return data.copyOfRange(offset, offset + n).also { offset += n } }
    fun remaining(): ByteArray = bytes(data.size - offset)

    private fun int(width: Int, bigEndian: Boolean): Long {
        val b = bytes(width)
        var v = 0L
        for (i in 0 until width) {
            val byte = b[if (bigEndian) i else width - 1 - i].toLong() and 0xFF
            v = (v shl 8) or byte
        }
        return v
    }

    fun u8(): UByte = int(1, false).toUByte()
    fun i8(): Byte = int(1, false).toByte()
    fun u16(bigEndian: Boolean = false): UShort = int(2, bigEndian).toUShort()
    fun i16(bigEndian: Boolean = false): Short = int(2, bigEndian).toShort()
    fun u32(bigEndian: Boolean = false): UInt = int(4, bigEndian).toUInt()
    fun i32(bigEndian: Boolean = false): Int = int(4, bigEndian).toInt()
    fun u64(bigEndian: Boolean = false): ULong = int(8, bigEndian).toULong()
    fun i64(bigEndian: Boolean = false): Long = int(8, bigEndian)
    fun f32(bigEndian: Boolean = false): Float = Float.fromBits(int(4, bigEndian).toInt())
    fun f64(bigEndian: Boolean = false): Double = Double.fromBits(int(8, bigEndian))
}

/** Constant bytes from the IDL (Anchor discriminators), written in hex. */
internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
`);

// Defined types
for (const t of program.definedTypes) {
  const name = pascal(t.name);
  let body;
  if (t.type.kind === 'enumTypeNode' && hasData(t.type)) {
    body = sealedEnum(name, t.type, t.docs);
  } else if (t.type.kind === 'enumTypeNode') {
    body = `${kdoc(t.docs)}enum class ${name} {\n${t.type.variants.map((v) => `${kdoc(v.docs, '    ')}    ${screaming(v.name)},`).join('\n')}\n}\n`;
  } else if (t.type.kind === 'structTypeNode') {
    body = structClass(name, t.type, t.docs);
  } else {
    unsupported.push(`defined type ${t.type.kind}`);
    continue;
  }
  files.set(join(pkgDir('types'), `${name}.kt`), `${header('types', ['com.solana.publickey.SolanaPublicKey', `${basePackage}.DataReader`, `${basePackage}.DataWriter`, `${basePackage}.hex`])}\n${body}`);
}

// Accounts
for (const a of program.accounts) {
  const name = pascal(a.name);
  const extra = a.size != null ? `        const val SIZE: Int = ${a.size}\n\n` : '';
  const body = structClass(name, a.data, a.docs, extra);
  files.set(join(pkgDir('accounts'), `${name}.kt`), `${header('accounts', ['com.solana.publickey.SolanaPublicKey', `${basePackage}.DataReader`, `${basePackage}.DataWriter`, `${basePackage}.hex`, `${basePackage}.types.*`])}\n${body}`);
}

// Errors
if (program.errors.length) {
  const name = `${programName}Error`;
  const entries = program.errors.map((e) => `${kdoc(e.docs, '    ')}    ${screaming(e.name)}(${e.code}, ${JSON.stringify(e.message)}),`).join('\n');
  files.set(join(pkgDir('errors'), `${name}.kt`), `${header('errors', [])}
enum class ${name}(val code: Int, val description: String) {
${entries}
    ;

    companion object {
        fun fromCode(code: Int): ${name}? = entries.firstOrNull { it.code == code }
    }
}
`);
}

// PDAs: one `find…Pda` per pdaNode, of this program and of the additional programs it ships with
// (SPL Token ships the associated token program). Same seed bytes as Codama's JS `find…Pda`.
function seedBytes(seed, params) {
  if (seed.kind === 'constantPdaSeedNode') {
    const { type, value } = seed;
    if (value.kind === 'programIdValueNode') return 'programId.bytes';
    if (value.kind === 'stringValueNode' && type.kind === 'stringTypeNode' && (type.encoding || 'utf8') === 'utf8') {
      return `${JSON.stringify(value.string)}.encodeToByteArray()`;
    }
    if (value.kind === 'bytesValueNode' && value.encoding === 'utf8') return `${JSON.stringify(value.data)}.encodeToByteArray()`;
    if (value.kind === 'bytesValueNode' && value.encoding === 'base16') return `hex("${value.data}")`;
    if (value.kind === 'publicKeyValueNode') return `SolanaPublicKey.from("${value.publicKey}").bytes`;
    if (value.kind === 'numberValueNode' && type.kind === 'numberTypeNode') {
      return `DataWriter().also { w -> ${writeStmts(type, num(type).lit(value.number)).join('; ')} }.toByteArray()`;
    }
    unsupported.push(`constant PDA seed ${type.kind} = ${value.kind}`);
    return 'TODO()';
  }
  if (seed.kind !== 'variablePdaSeedNode') { unsupported.push(`PDA seed ${seed.kind}`); return 'TODO()'; }
  const n = ident(seed.name);
  const { type } = seed;
  if (type.kind === 'publicKeyTypeNode') { params.push({ name: n, kt: 'SolanaPublicKey', docs: seed.docs }); return `${n}.bytes`; }
  if (type.kind === 'stringTypeNode' && (type.encoding || 'utf8') === 'utf8') {
    // A string seed is its raw UTF-8 bytes, no length prefix.
    params.push({ name: n, kt: 'String', docs: seed.docs });
    return `${n}.encodeToByteArray()`;
  }
  if (type.kind === 'bytesTypeNode') { params.push({ name: n, kt: 'ByteArray', docs: seed.docs }); return n; }
  // Anything else (numbers, structs…) is written with its own encoding.
  params.push({ name: n, kt: ktType(type, pascal(seed.name)), docs: seed.docs });
  return `DataWriter().also { w -> ${writeStmts(type, n, pascal(seed.name)).join('; ')} }.toByteArray()`;
}

const pdaPrograms = [program, ...(root.additionalPrograms || [])];
for (const p of pdaPrograms) {
  const defaultProgramId = p === program ? `${programObject}.PROGRAM_ID` : `SolanaPublicKey.from("${p.publicKey}")`;
  for (const pda of p.pdas || []) {
    const params = [];
    const seeds = pda.seeds.map((s) => seedBytes(s, params));
    const programId = pda.programId ? `SolanaPublicKey.from("${pda.programId}")` : defaultProgramId;
    const paramDocs = params.filter((q) => q.docs && q.docs.length).map((q) => `@param ${q.name.replace(/`/g, '')} ${q.docs.join(' ')}`);
    const docs = [...(pda.docs || []), ...(p === program ? [] : [`A PDA of the ${p.name} program.`]),
      ...(paramDocs.length ? ['', ...paramDocs] : []), '@return the address and its bump.'];
    const name = pascal(pda.name);
    const code = seeds.join(' ') + params.map((q) => q.kt).join(' ') + programId;
    files.set(join(pkgDir('pdas'), `${name}Pda.kt`), `${header('pdas', [
      'com.solana.publickey.ProgramDerivedAddress',
      'com.solana.publickey.SolanaPublicKey',
      ...(code.includes(programObject) ? [`${basePackage}.${programObject}`] : []),
      ...(code.includes('DataWriter') ? [`${basePackage}.DataWriter`] : []),
      ...(code.includes('hex(') ? [`${basePackage}.hex`] : []),
      `${basePackage}.types.*`,
    ])}
${kdoc(docs)}suspend fun find${name}Pda(
${[...params.map((q) => `    ${q.name}: ${q.kt},`), `    programId: SolanaPublicKey = ${programId},`].join('\n')}
): ProgramDerivedAddress = ProgramDerivedAddress.find(
    listOf(
${seeds.map((s) => `        ${s},`).join('\n')}
    ),
    programId,
).getOrThrow()
`);
  }
}

// Instructions
for (const ix of program.instructions) {
  const fnName = `${camel(ix.name)}Instruction`;
  const params = []; // { name, kt, def, docs }
  const metas = [];
  const remainingSignerArgs = (ix.remainingAccounts || [])
    .filter((r) => r.isSigner === true && r.value.kind === 'argumentValueNode')
    .map((r) => ident(r.value.name));

  for (const acc of ix.accounts) {
    const n = ident(acc.name);
    let def = null;
    if (acc.defaultValue) {
      def = valueLiteral(acc.defaultValue, { kind: 'publicKeyTypeNode' });
      if (def === null && acc.defaultValue.kind !== 'identityValueNode') unsupported.push(`account default ${acc.defaultValue.kind} (${ix.name}.${acc.name}) -> made required`);
    }
    // Optional accounts keep their IDL default; passing null explicitly falls back to the program id.
    if (acc.isOptional && def === null) def = 'null';
    params.push({ name: n, kt: acc.isOptional ? 'SolanaPublicKey?' : 'SolanaPublicKey', def, docs: acc.docs });

    let signer;
    if (acc.isSigner === 'either') {
      if (remainingSignerArgs.length) signer = remainingSignerArgs.map((r) => `${r}.isEmpty()`).join(' && ');
      else { params.push({ name: `${n}IsSigner`, kt: 'Boolean', def: 'true', docs: [`Whether \`${acc.name}\` signs this instruction.`] }); signer = `${n}IsSigner`; }
    } else signer = acc.isSigner ? 'true' : 'false';
    if (acc.isOptional) {
      // optionalAccountStrategy "programId": a missing optional account is replaced by the program id.
      metas.push(`add(if (${n} != null) AccountMeta(${n}, isSigner = ${signer}, isWritable = ${acc.isWritable}) else AccountMeta(programId, isSigner = false, isWritable = false))`);
    } else metas.push(`add(AccountMeta(${n}, isSigner = ${signer}, isWritable = ${acc.isWritable}))`);
  }

  const dataStmts = [];
  // An argument may share its name with an account (Squads: configAuthority); the argument gets "Arg".
  const accountNames = new Set(ix.accounts.map((a) => a.name));
  for (const arg of ix.arguments) {
    const n = ident(accountNames.has(arg.name) ? `${arg.name}Arg` : arg.name);
    const hint = pascal(ix.name) + pascal(arg.name);
    if (arg.defaultValueStrategy === 'omitted' && arg.defaultValue) {
      dataStmts.push(...writeStmts(arg.type, valueLiteral(arg.defaultValue, arg.type), hint));
      continue;
    }
    const def = arg.defaultValue ? valueLiteral(arg.defaultValue, arg.type) : null;
    if (arg.defaultValue && def === null) unsupported.push(`argument default ${arg.defaultValue.kind} (${ix.name}.${arg.name}) -> made required`);
    params.push({ name: n, kt: ktType(arg.type, hint), def, docs: arg.docs });
    dataStmts.push(...writeStmts(arg.type, n, hint));
  }

  for (const r of ix.remainingAccounts || []) {
    if (r.value.kind !== 'argumentValueNode') { unsupported.push(`remaining accounts ${r.value.kind} (${ix.name})`); continue; }
    const n = ident(r.value.name);
    params.push({ name: n, kt: 'List<SolanaPublicKey>', def: r.isOptional ? 'emptyList()' : null, docs: r.docs });
    metas.push(`${n}.forEach { add(AccountMeta(it, isSigner = ${r.isSigner === true}, isWritable = ${r.isWritable === true})) }`);
  }
  params.push({ name: 'programId', kt: 'SolanaPublicKey', def: `${programObject}.PROGRAM_ID`, docs: [] });

  // Required parameters first so positional calls stay natural.
  const ordered = [...params.filter((p) => p.def === null), ...params.filter((p) => p.def !== null)];
  const paramDocs = params.filter((p) => p.docs && p.docs.length).map((p) => `@param ${p.name.replace(/`/g, '')} ${p.docs.join(' ')}`);
  const docs = [...(ix.docs || []), ...(paramDocs.length ? ['', ...paramDocs] : [])];

  const body = `${kdoc(docs)}fun ${fnName}(
${ordered.map((p) => `    ${p.name}: ${p.kt}${p.def !== null ? ' = ' + p.def : ''},`).join('\n')}
): TransactionInstruction {
    val w = DataWriter()
${dataStmts.map((l) => `    ${l}`).join('\n')}
    return TransactionInstruction(
        programId,
${metas.length ? `        buildList {\n${metas.map((m) => `            ${m}`).join('\n')}\n        },` : '        emptyList(),'}
        w.toByteArray(),
    )
}
`;
  files.set(join(pkgDir('instructions'), `${pascal(ix.name)}.kt`), `${header('instructions', [
    'com.solana.publickey.SolanaPublicKey',
    'com.solana.transaction.AccountMeta',
    'com.solana.transaction.TransactionInstruction',
    `${basePackage}.${programObject}`,
    `${basePackage}.DataWriter`,
    `${basePackage}.DataReader`,
    `${basePackage}.hex`,
    `${basePackage}.types.*`,
  ])}\n${body}`);
}

// Inline structs discovered while rendering instructions (e.g. batch items)
for (const { name, struct } of inlineStructs) {
  files.set(join(pkgDir('types'), `${name}.kt`), `${header('types', ['com.solana.publickey.SolanaPublicKey', `${basePackage}.DataReader`, `${basePackage}.DataWriter`, `${basePackage}.hex`])}\n${structClass(name, struct, [])}`);
}

if (unsupported.length) {
  console.error('Unsupported Codama nodes:\n  ' + [...new Set(unsupported)].join('\n  '));
  process.exit(2);
}

// Only import the types package when it exists.
const hasTypes = program.definedTypes.length > 0 || inlineStructs.length > 0;
rmSync(pkgDir(''), { recursive: true, force: true });
for (const [path, content] of files) {
  mkdirSync(join(path, '..'), { recursive: true });
  writeFileSync(path, hasTypes ? content : content.replace(`import ${basePackage}.types.*\n`, ''));
}
console.log(`Generated ${files.size} Kotlin files for program "${program.name}" (${program.instructions.length} instructions, ${program.accounts.length} accounts, ${program.definedTypes.length} types, ${program.errors.length} errors) into ${pkgDir('')}`);
