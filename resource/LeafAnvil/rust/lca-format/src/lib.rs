//! Pure-Rust `LeafCraft Anvil` (`.lca`, `LCA-ICD-1.0`) Layer 1-4 Codec, `CBIT T1..T8` Tripwires,
//! 4-Tier `SEC-DED` Self-Healing Engine, and `PumpkinMC` Direct `SectionFrame` Bridge (`leafanvil-ADR-004`).
//!
//! @governedBy leafanvil-ADR-004
//! @governedBy anvil-api-ADR-003
//! @governedBy anvil-api-ADR-002
//! @governedBy ADR-115
//! @trace REQ-RTP-S-004
//! @trace REQ-RTP-S-005

use std::fmt;

pub const LCA_SUPERBLOCK_MAGIC: u64 = 0x4C43_415F_5630_3031; // "LCA_V001"
pub const LCA_FOOTER_CANARY_MAGIC: u64 = 0x4C43_4131_5F45_4E44; // "LCA1_END"
pub const LCA_FORMAT_VERSION: u16 = 2;
pub const SUPERBLOCK_SIZE: usize = 64;
pub const POINTER_DIRECTORY_OFFSET: usize = 64;
pub const POINTER_DIRECTORY_SIZE: usize = 8192;
pub const DICT_ROOT_OFFSET: usize = 0x2040;
pub const SECDED_TABLE_SIZE: usize = 1024;
pub const FOOTER_SIZE: usize = 8;
pub const SLAB_SHIFT: usize = 8;
pub const SLAB_BYTES: usize = 256;
pub const CHUNK_FRAME_SELF_TAG_MASK: u32 = 0xFC00_0000;
pub const CHUNK_FRAME_SELF_TAG_MAGIC: u32 = 0x5800_0000;
pub const LCA_DICT_FRAME_MAGIC: u16 = 0x4C44; // "LD"
pub const DICT_HUFF_HEADER_BYTES: usize = 384;
pub const MAX_CHUNK_UNCOMPRESSED_BYTES: usize = 16 * 1024 * 1024;
pub const SECTION_VOXELS: usize = 4096;
pub const BIOME_CELLS: usize = 64;

pub const FNV1A_64_OFFSET_BASIS: u64 = 0xcbf2_9ce4_8422_2325;
pub const FNV1A_64_PRIME: u64 = 0x0000_0100_0000_01b3;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LcaError {
    CorruptRegionEntry(String),
}

impl fmt::Display for LcaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            LcaError::CorruptRegionEntry(msg) => write!(f, "{}", msg),
        }
    }
}

impl std::error::Error for LcaError {}

pub type LcaResult<T> = Result<T, LcaError>;

#[inline]
pub fn read_u16_be(buf: &[u8], off: usize) -> u16 {
    ((buf[off] as u16) << 8) | (buf[off + 1] as u16)
}

#[inline]
pub fn read_u32_be(buf: &[u8], off: usize) -> u32 {
    ((buf[off] as u32) << 24)
        | ((buf[off + 1] as u32) << 16)
        | ((buf[off + 2] as u32) << 8)
        | (buf[off + 3] as u32)
}

#[inline]
pub fn read_i32_be(buf: &[u8], off: usize) -> i32 {
    read_u32_be(buf, off) as i32
}

#[inline]
pub fn read_u64_be(buf: &[u8], off: usize) -> u64 {
    ((buf[off] as u64) << 56)
        | ((buf[off + 1] as u64) << 48)
        | ((buf[off + 2] as u64) << 40)
        | ((buf[off + 3] as u64) << 32)
        | ((buf[off + 4] as u64) << 24)
        | ((buf[off + 5] as u64) << 16)
        | ((buf[off + 6] as u64) << 8)
        | (buf[off + 7] as u64)
}

#[inline]
pub fn write_u64_be(buf: &mut [u8], off: usize, v: u64) {
    buf[off..off + 8].copy_from_slice(&v.to_be_bytes());
}

#[inline]
pub fn read_varint(buf: &[u8], pos: usize, limit: usize) -> LcaResult<(usize, usize)> {
    let mut val: usize = 0;
    let mut shift: u32 = 0;
    let mut p = pos;
    while p < limit && p < buf.len() {
        let b = buf[p] as usize;
        p += 1;
        val |= (b & 0x7F) << shift;
        if (b & 0x80) == 0 {
            return Ok((val, p - pos));
        }
        shift += 7;
        if shift > 28 {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT: VarInt overflow at byte offset {}",
                pos
            )));
        }
    }
    Err(LcaError::CorruptRegionEntry(format!(
        "CBIT: Truncated VarInt at byte offset {} (limit={})",
        pos, limit
    )))
}

#[inline]
pub fn varint_len(mut v: usize) -> usize {
    let mut len = 1;
    while (v & !0x7F) != 0 {
        len += 1;
        v >>= 7;
    }
    len
}

/// Layer 1: Superblock, Atomic Pointer Directory (`35|1|20|8`), Extended Hamming `(72, 64)` `SEC-DED`,
/// in-memory `CoW` free-slab bitmap allocator, and `"LCA1_END"` footer canary (`LCA-ICD-1.0` Section 4).
pub mod layer1 {
    use super::*;

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct Superblock {
        pub format_version: u16,
        pub storage_kind: u8,
        pub flags: u8,
        pub region_x: i32,
        pub region_z: i32,
        pub min_section_y: i32,
        pub section_count: usize,
        pub slab_shift: u16,
        pub default_data_version: i32,
        pub dict_root_offset: usize,
        pub dict_root_capacity: usize,
        pub secded_table_offset: usize,
        pub secded_table_len: usize,
        pub slab_pool_base_offset: usize,
        pub header_checksum: u64,
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub struct ChunkPointer64 {
        pub raw: u64,
        pub slab_offset35: usize,
        pub codec1: u8,
        pub exact_byte_len20: usize,
        pub generation8: u8,
    }

    impl ChunkPointer64 {
        #[inline]
        pub fn unpack(raw: u64) -> Self {
            Self {
                raw,
                slab_offset35: ((raw >> 29) & 0x7_FFFF_FFFF) as usize,
                codec1: ((raw >> 28) & 1) as u8,
                exact_byte_len20: ((raw >> 8) & 0xFFFFF) as usize,
                generation8: (raw & 0xFF) as u8,
            }
        }

        #[inline]
        pub fn pack(slab_offset35: usize, mode_b: bool, exact_byte_len20: usize, generation8: u8) -> u64 {
            (((slab_offset35 as u64) & 0x7_FFFF_FFFF) << 29)
                | (if mode_b { 1u64 << 28 } else { 0 })
                | (((exact_byte_len20 as u64) & 0xFFFFF) << 8)
                | (generation8 as u64)
        }

        #[inline]
        pub fn is_present(&self) -> bool {
            self.raw != 0 && self.slab_offset35 > 0 && self.exact_byte_len20 > 0
        }

        #[inline]
        pub fn byte_offset(&self) -> usize {
            self.slab_offset35 << SLAB_SHIFT
        }
    }

    pub struct SecDedTables {
        pub data_masks: [u8; 64],
        pub syndrome_to_bit: [i8; 128],
        pub byte_lut: [[u8; 256]; 8],
    }

    impl SecDedTables {
        pub fn build() -> Self {
            let mut data_masks = [0u8; 64];
            let mut syndrome_to_bit = [-1i8; 128];
            let mut bit_idx = 0usize;
            let mut code = 3u8;
            while bit_idx < 64 {
                if code.count_ones() >= 2 {
                    data_masks[bit_idx] = code;
                    syndrome_to_bit[code as usize] = bit_idx as i8;
                    bit_idx += 1;
                }
                code += 1;
            }
            let mut byte_lut = [[0u8; 256]; 8];
            for byte_pos in 0..8 {
                let base_bit = byte_pos << 3;
                for v in 0..256usize {
                    let mut syn7 = 0u8;
                    for b in 0..8 {
                        if (v & (1 << b)) != 0 {
                            syn7 ^= data_masks[base_bit + b];
                        }
                    }
                    let overall_parity = (((v as u32).count_ones() ^ (syn7 as u32).count_ones()) & 1) as u8;
                    byte_lut[byte_pos][v] = (overall_parity << 7) | syn7;
                }
            }
            Self {
                data_masks,
                syndrome_to_bit,
                byte_lut,
            }
        }
    }

    pub fn secded_tables() -> &'static SecDedTables {
        use std::sync::OnceLock;
        static TABLES: OnceLock<SecDedTables> = OnceLock::new();
        TABLES.get_or_init(SecDedTables::build)
    }

    #[inline]
    pub fn compute_hamming_secded8(word64: u64) -> u8 {
        let t = &secded_tables().byte_lut;
        t[0][(word64 & 0xFF) as usize]
            ^ t[1][((word64 >> 8) & 0xFF) as usize]
            ^ t[2][((word64 >> 16) & 0xFF) as usize]
            ^ t[3][((word64 >> 24) & 0xFF) as usize]
            ^ t[4][((word64 >> 32) & 0xFF) as usize]
            ^ t[5][((word64 >> 40) & 0xFF) as usize]
            ^ t[6][((word64 >> 48) & 0xFF) as usize]
            ^ t[7][((word64 >> 56) & 0xFF) as usize]
    }

    /// Evaluates a 64-bit word against its stored 8-bit Extended Hamming `(72, 64)` `SEC-DED` syndrome.
    /// Returns `Some((healed_word, was_data_bit_healed))` on clean or 1-bit `SEC`, or `None` on 2-bit `DED`.
    #[inline]
    pub fn heal_single_bit_flip64(word64: u64, stored_syndrome8: u8) -> Option<(u64, bool)> {
        let expected_syn8 = compute_hamming_secded8(word64);
        let diff8 = expected_syn8 ^ stored_syndrome8;
        if diff8 == 0 {
            return Some((word64, false));
        }
        let err_syn7 = diff8 & 0x7F;
        let total_flipped_parity =
            (word64.count_ones() ^ (stored_syndrome8 & 0x7F).count_ones() ^ ((stored_syndrome8 >> 7) as u32 & 1))
                & 1;
        if total_flipped_parity == 1 {
            if err_syn7.count_ones() <= 1 {
                return Some((word64, false));
            }
            let corrupted_bit = secded_tables().syndrome_to_bit[err_syn7 as usize];
            if corrupted_bit >= 0 {
                return Some((word64 ^ (1u64 << (corrupted_bit as u32)), true));
            }
        }
        None
    }

    #[inline]
    pub fn compute_superblock_checksum64(buf: &[u8], offset: usize) -> u64 {
        let mut h: u64 = 0x9E37_79B9_7F4A_7C15;
        for i in 0..7 {
            let mut k = read_u64_be(buf, offset + (i << 3));
            k = k.wrapping_mul(0xBF58_476D_1CE4_E5B9);
            k = k.rotate_left(31);
            k = k.wrapping_mul(0x94D0_49BB_1331_11EB);
            h ^= k;
            h = h.rotate_left(27).wrapping_mul(5).wrapping_add(0x52DC_E729);
        }
        h ^= h >> 33;
        h = h.wrapping_mul(0xFF51_AFD7_ED55_8CCD);
        h ^= h >> 33;
        h = h.wrapping_mul(0xC4CE_B9FE_1A85_EC53);
        h ^= h >> 33;
        h
    }

    pub fn parse_and_validate_superblock(region_bytes: &[u8]) -> LcaResult<Superblock> {
        let min_len = DICT_ROOT_OFFSET + 256 + SECDED_TABLE_SIZE + FOOTER_SIZE;
        if region_bytes.len() < min_len {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T1: Region file too short ({} < {})",
                region_bytes.len(),
                min_len
            )));
        }
        let magic = read_u64_be(region_bytes, 0x00);
        if magic != LCA_SUPERBLOCK_MAGIC {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T1: Invalid superblock magic 0x{:016x}",
                magic
            )));
        }
        let footer = read_u64_be(region_bytes, region_bytes.len() - FOOTER_SIZE);
        if footer != LCA_FOOTER_CANARY_MAGIC {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T1: Invalid or missing LCA1_END footer canary 0x{:016x}",
                footer
            )));
        }
        let format_version = read_u16_be(region_bytes, 0x08);
        if format_version == 0 || format_version > LCA_FORMAT_VERSION {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T1: Unsupported formatVersion {}",
                format_version
            )));
        }
        let storage_kind = region_bytes[0x0A];
        let flags = region_bytes[0x0B];
        let region_x = read_i32_be(region_bytes, 0x0C);
        let region_z = read_i32_be(region_bytes, 0x10);
        let min_section_y = read_i32_be(region_bytes, 0x14);
        let section_count = read_u16_be(region_bytes, 0x18) as usize;
        let slab_shift = read_u16_be(region_bytes, 0x1A);
        if slab_shift != SLAB_SHIFT as u16 || section_count == 0 || section_count > 128 {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T1: Invalid slabShift={} or sectionCount={}",
                slab_shift, section_count
            )));
        }
        let default_data_version = read_i32_be(region_bytes, 0x1C);
        let dict_offset_and_len = read_u64_be(region_bytes, 0x20);
        let dict_root_capacity = (dict_offset_and_len >> 32) as usize;
        let dict_root_offset = (dict_offset_and_len & 0xFFFF_FFFF) as usize;
        let lod_and_secded = read_u64_be(region_bytes, 0x28);
        let secded_table_len = (lod_and_secded >> 32) as usize;
        let secded_table_offset = if secded_table_len >= SECDED_TABLE_SIZE {
            (lod_and_secded & 0xFFFF_FFFF) as usize
        } else {
            (dict_root_offset + dict_root_capacity + 255) & !255
        };
        let slab_pool_base_offset = read_u64_be(region_bytes, 0x30) as usize;
        let stored_checksum = read_u64_be(region_bytes, 0x38);
        let expected_checksum = compute_superblock_checksum64(region_bytes, 0);
        if stored_checksum != expected_checksum {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T2: Superblock checksum mismatch (stored=0x{:016x}, expected=0x{:016x})",
                stored_checksum, expected_checksum
            )));
        }
        if dict_root_offset != DICT_ROOT_OFFSET
            || dict_root_capacity < 256
            || secded_table_offset + SECDED_TABLE_SIZE > slab_pool_base_offset
            || slab_pool_base_offset > region_bytes.len() - FOOTER_SIZE
        {
            return Err(LcaError::CorruptRegionEntry(
                "CBIT T1: Superblock directory/SecDed/slabPool offsets out of bounds".to_string(),
            ));
        }

        Ok(Superblock {
            format_version,
            storage_kind,
            flags,
            region_x,
            region_z,
            min_section_y,
            section_count,
            slab_shift,
            default_data_version,
            dict_root_offset,
            dict_root_capacity,
            secded_table_offset,
            secded_table_len,
            slab_pool_base_offset,
            header_checksum: stored_checksum,
        })
    }

    /// Reconstructed in-memory `CoW` free-slab allocator (`leafanvil-ADR-004` Section 1.1).
    #[derive(Debug, Clone)]
    pub struct FreeSlabAllocator {
        pub slab_pool_base_slab: usize,
        pub active_end_slab: usize,
        pub free_intervals: Vec<(usize, usize)>,
    }

    impl FreeSlabAllocator {
        pub fn reconstruct(
            region_bytes: &[u8],
            sb: &Superblock,
            dict_segments: &[(usize, usize)],
        ) -> Self {
            let base_slab = sb.slab_pool_base_offset >> SLAB_SHIFT;
            let file_end_slab = (region_bytes.len().saturating_sub(FOOTER_SIZE)) >> SLAB_SHIFT;
            let mut occupied: Vec<(usize, usize)> = Vec::new();
            for &(seg_off, seg_cap) in dict_segments {
                let s = seg_off >> SLAB_SHIFT;
                if s >= base_slab {
                    let e = s + ((seg_cap + 255) >> SLAB_SHIFT);
                    occupied.push((s, e));
                }
            }
            for i in 0..1024 {
                let raw = read_u64_be(region_bytes, POINTER_DIRECTORY_OFFSET + (i << 3));
                let ptr = ChunkPointer64::unpack(raw);
                if ptr.is_present() && ptr.slab_offset35 >= base_slab {
                    let e = ptr.slab_offset35 + ((ptr.exact_byte_len20 + 255) >> SLAB_SHIFT);
                    occupied.push((ptr.slab_offset35, e));
                }
            }
            occupied.sort_unstable();
            let mut cursor = base_slab;
            let mut free_intervals = Vec::new();
            for (s, e) in occupied {
                if s > cursor {
                    free_intervals.push((cursor, s - cursor));
                }
                if e > cursor {
                    cursor = e;
                }
            }
            let active_end_slab = cursor.max(file_end_slab).max(base_slab);
            Self {
                slab_pool_base_slab: base_slab,
                active_end_slab,
                free_intervals: Vec::from_iter(free_intervals),
            }
        }

        pub fn allocate(&mut self, needed_slabs: usize) -> usize {
            let needed = needed_slabs.max(1);
            for idx in 0..self.free_intervals.len() {
                let (start, len) = self.free_intervals[idx];
                if len >= needed {
                    if len == needed {
                        self.free_intervals.remove(idx);
                    } else {
                        self.free_intervals[idx] = (start + needed, len - needed);
                    }
                    return start;
                }
            }
            let start = self.active_end_slab;
            self.active_end_slab += needed;
            start
        }

        pub fn free(&mut self, start_slab: usize, count: usize) {
            if count == 0 || start_slab < self.slab_pool_base_slab {
                return;
            }
            self.free_intervals.push((start_slab, count));
            self.free_intervals.sort_unstable_by_key(|&(s, _)| s);
            let mut merged: Vec<(usize, usize)> = Vec::with_capacity(self.free_intervals.len());
            for (s, len) in self.free_intervals.drain(..) {
                if let Some(last) = merged.last_mut() {
                    if last.0 + last.1 >= s {
                        let new_end = (last.0 + last.1).max(s + len);
                        last.1 = new_end - last.0;
                        continue;
                    }
                }
                merged.push((s, len));
            }
            if let Some(&(last_s, last_len)) = merged.last() {
                if last_s + last_len == self.active_end_slab {
                    self.active_end_slab = last_s.max(self.slab_pool_base_slab);
                    merged.pop();
                }
            }
            self.free_intervals = merged;
        }
    }
}

/// Layer 2: Append-only Chained Shared `BlockState` & `Biome` Dictionary, pre-seeded static tables,
/// and Mode B (`LCD2`) 12-bit Canonical Huffman / pattern arena synthesis (`LCA-ICD-1.0` Section 5).
pub mod layer2 {
    use super::layer1::{compute_hamming_secded8, secded_tables, Superblock};
    use super::*;

    pub const STATIC_CORE_STATES: [&str; 160] = [
        "minecraft:air", "minecraft:stone", "minecraft:deepslate", "minecraft:dirt",
        "minecraft:grass_block", "minecraft:podzol", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
        "minecraft:mycelium", "minecraft:dirt_path", "minecraft:farmland", "minecraft:mud",
        "minecraft:packed_mud", "minecraft:clay", "minecraft:gravel", "minecraft:sand",
        "minecraft:red_sand", "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:bedrock",
        "minecraft:water", "minecraft:lava", "minecraft:granite", "minecraft:diorite",
        "minecraft:andesite", "minecraft:tuff", "minecraft:calcite", "minecraft:dripstone_block",
        "minecraft:pointed_dripstone", "minecraft:smooth_basalt", "minecraft:basalt", "minecraft:blackstone",
        "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:cobblestone", "minecraft:mossy_cobblestone",
        "minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
        "minecraft:copper_ore", "minecraft:deepslate_copper_ore", "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
        "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
        "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
        "minecraft:raw_iron_block", "minecraft:raw_copper_block", "minecraft:raw_gold_block", "minecraft:amethyst_block",
        "minecraft:budding_amethyst", "minecraft:amethyst_cluster", "minecraft:snow", "minecraft:snow_block",
        "minecraft:powder_snow", "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
        "minecraft:moss_block", "minecraft:moss_carpet", "minecraft:glow_lichen", "minecraft:vine",
        "minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:spore_blossom", "minecraft:hanging_roots",
        "minecraft:big_dripleaf", "minecraft:big_dripleaf_stem", "minecraft:small_dripleaf", "minecraft:azalea",
        "minecraft:flowering_azalea", "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
        "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log",
        "minecraft:cherry_log", "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
        "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves", "minecraft:mangrove_leaves",
        "minecraft:cherry_leaves", "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves", "minecraft:short_grass",
        "minecraft:grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
        "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid", "minecraft:allium",
        "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip", "minecraft:white_tulip",
        "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
        "minecraft:sweet_berry_bush", "minecraft:pumpkin", "minecraft:melon", "minecraft:sugar_cane",
        "minecraft:bamboo", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass",
        "minecraft:tall_seagrass", "minecraft:cave_air", "minecraft:void_air", "minecraft:infested_stone",
        "minecraft:infested_deepslate", "minecraft:sculk", "minecraft:sculk_vein", "minecraft:netherrack",
        "minecraft:magma_block", "minecraft:bubble_column", "minecraft:firefly_bush", "minecraft:brown_mushroom",
        "minecraft:small_amethyst_bud", "minecraft:medium_amethyst_bud", "minecraft:large_amethyst_bud", "minecraft:oak_fence",
        "minecraft:oak_planks", "minecraft:cobweb", "minecraft:chest", "minecraft:spawner",
        "minecraft:rail", "minecraft:wall_torch", "minecraft:end_stone", "minecraft:soul_sand",
        "minecraft:soul_soil", "minecraft:glowstone", "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore",
        "minecraft:crimson_nylium", "minecraft:warped_nylium", "minecraft:nether_wart_block", "minecraft:warped_wart_block",
        "minecraft:shroomlight", "minecraft:ancient_debris", "minecraft:bone_block", "minecraft:stone_bricks",
        "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks", "minecraft:torch", "minecraft:lantern",
    ];

    pub const STATIC_CORE_PROP_SUFFIXES: [&str; 128] = [
        "[axis=y]", "[axis=x]", "[axis=z]",
        "[lit=false]", "[lit=true]",
        "[snowy=false]", "[snowy=true]",
        "[waterlogged=false]", "[waterlogged=true]",
        "[half=lower]", "[half=upper]", "[half=bottom]", "[half=top]",
        "[drag=true]", "[drag=false]",
        "[berries=false]", "[berries=true]",
        "[level=0]", "[level=1]", "[level=2]", "[level=3]", "[level=4]", "[level=5]", "[level=6]", "[level=7]",
        "[level=8]", "[level=9]", "[level=10]", "[level=11]", "[level=12]", "[level=13]", "[level=14]", "[level=15]",
        "[layers=1]", "[layers=2]", "[layers=3]", "[layers=4]", "[layers=5]", "[layers=6]", "[layers=7]", "[layers=8]",
        "[distance=1,persistent=false,waterlogged=false]", "[distance=2,persistent=false,waterlogged=false]",
        "[distance=3,persistent=false,waterlogged=false]", "[distance=4,persistent=false,waterlogged=false]",
        "[distance=5,persistent=false,waterlogged=false]", "[distance=6,persistent=false,waterlogged=false]",
        "[distance=7,persistent=false,waterlogged=false]",
        "[persistent=false,waterlogged=false,distance=1]", "[persistent=false,waterlogged=false,distance=2]",
        "[persistent=false,waterlogged=false,distance=3]", "[persistent=false,waterlogged=false,distance=4]",
        "[persistent=false,waterlogged=false,distance=5]", "[persistent=false,waterlogged=false,distance=6]",
        "[persistent=false,waterlogged=false,distance=7]",
        "[facing=up,waterlogged=false]", "[facing=down,waterlogged=false]",
        "[facing=north,waterlogged=false]", "[facing=south,waterlogged=false]",
        "[facing=east,waterlogged=false]", "[facing=west,waterlogged=false]",
        "[facing=north]", "[facing=south]", "[facing=east]", "[facing=west]", "[facing=up]", "[facing=down]",
        "[age=0]", "[age=1]", "[age=2]", "[age=3]", "[age=4]", "[age=5]", "[age=6]", "[age=7]",
        "[age=8]", "[age=9]", "[age=10]", "[age=11]", "[age=12]", "[age=13]", "[age=14]", "[age=15]",
        "[age=16]", "[age=17]", "[age=18]", "[age=19]", "[age=20]", "[age=21]", "[age=22]", "[age=23]",
        "[age=24]", "[age=25]",
        "[age=20,berries=false]", "[age=21,berries=false]", "[age=22,berries=false]",
        "[age=23,berries=false]", "[age=24,berries=false]", "[age=25,berries=false]",
        "[age=23,berries=true]", "[age=24,berries=true]", "[age=25,berries=true]",
        "[waterlogged=false,shape=east_west]", "[waterlogged=false,shape=north_south]",
        "[shape=north_south,waterlogged=false]", "[shape=east_west,waterlogged=false]",
        "[stage=0]", "[stage=1]",
        "[hanging=false,waterlogged=false]", "[hanging=true,waterlogged=false]",
        "[type=bottom,waterlogged=false]", "[type=top,waterlogged=false]", "[type=double,waterlogged=false]",
        "[east=false,north=false,south=false,up=false,west=false]",
        "[bites=0]", "[moisture=0]", "[moisture=7]",
        "[distance=7,persistent=true,waterlogged=false]", "[distance=1,persistent=true,waterlogged=false]",
        "[eye=false,facing=north]", "[eye=false,facing=south]", "[eye=false,facing=east]", "[eye=false,facing=west]",
        "[pickles=1,waterlogged=true]", "[candles=1,lit=false,waterlogged=false]",
        "[facing=north,type=single,waterlogged=false]", "[facing=south,type=single,waterlogged=false]",
        "[facing=east,type=single,waterlogged=false]",
    ];

    pub const STATIC_CORE_BIOMES: [&str; 65] = [
        "minecraft:plains", "minecraft:sunflower_plains", "minecraft:snowy_plains", "minecraft:ice_spikes",
        "minecraft:desert", "minecraft:swamp", "minecraft:mangrove_swamp", "minecraft:forest",
        "minecraft:flower_forest", "minecraft:birch_forest", "minecraft:dark_forest", "minecraft:old_growth_birch_forest",
        "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga", "minecraft:taiga", "minecraft:snowy_taiga",
        "minecraft:savanna", "minecraft:savanna_plateau", "minecraft:windswept_hills", "minecraft:windswept_gravelly_hills",
        "minecraft:windswept_forest", "minecraft:windswept_savanna", "minecraft:jungle", "minecraft:sparse_jungle",
        "minecraft:bamboo_jungle", "minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands",
        "minecraft:meadow", "minecraft:cherry_grove", "minecraft:grove", "minecraft:snowy_slopes",
        "minecraft:frozen_peaks", "minecraft:jagged_peaks", "minecraft:stony_peaks", "minecraft:river",
        "minecraft:frozen_river", "minecraft:beach", "minecraft:snowy_beach", "minecraft:stony_shore",
        "minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean", "minecraft:ocean",
        "minecraft:deep_ocean", "minecraft:cold_ocean", "minecraft:deep_cold_ocean", "minecraft:frozen_ocean",
        "minecraft:deep_frozen_ocean", "minecraft:mushroom_fields", "minecraft:dripstone_caves", "minecraft:lush_caves",
        "minecraft:deep_dark", "minecraft:nether_wastes", "minecraft:crimson_forest", "minecraft:warped_forest",
        "minecraft:soul_sand_valley", "minecraft:basalt_deltas", "minecraft:the_end", "minecraft:the_void",
        "minecraft:small_end_islands", "minecraft:end_midlands", "minecraft:end_highlands", "minecraft:end_barrens",
        "minecraft:pale_garden",
    ];

    #[inline]
    pub fn strip_block_properties(canonical: &str) -> &str {
        match canonical.find('[') {
            Some(idx) => &canonical[..idx],
            None => canonical,
        }
    }

    #[inline]
    pub fn read_header_word64_be(buf: &[u8], base_offset: usize, header_len: usize, word_idx: usize) -> u64 {
        let rel = word_idx << 3;
        let rem = header_len.saturating_sub(rel);
        if rem >= 8 {
            return read_u64_be(buf, base_offset + rel);
        }
        let mut v = 0u64;
        for b in 0..8 {
            let byte_val = if b < rem { buf[base_offset + rel + b] as u64 } else { 0 };
            v = (v << 8) | byte_val;
        }
        v
    }

    #[inline]
    pub fn write_header_word64_be(buf: &mut [u8], base_offset: usize, header_len: usize, word_idx: usize, word64: u64) {
        let rel = word_idx << 3;
        let rem = header_len.saturating_sub(rel);
        if rem >= 8 {
            write_u64_be(buf, base_offset + rel, word64);
            return;
        }
        for b in 0..rem {
            buf[base_offset + rel + b] = ((word64 >> ((7 - b) << 3)) & 0xFF) as u8;
        }
    }

    /// Verifies and algebraically heals any 1-bit flip in each active 64-bit word of a Layer 2
    /// dictionary segment (`[seg_base_off .. seg_base_off + 8 + used_payload_bytes)`) using the
    /// downward-growing `(72, 64)` `SEC-DED` syndromes stored at `seg_payload_limit - 1 - w`.
    pub fn verify_and_heal_dict_segment_secded(
        seg_buf: &mut [u8],
        seg_base_off: usize,
        seg_cap: usize,
    ) -> LcaResult<usize> {
        if seg_cap < 16 || seg_base_off + seg_cap > seg_buf.len() {
            return Err(LcaError::CorruptRegionEntry(
                "CBIT T5: Dictionary segment out of bounds".to_string(),
            ));
        }
        let seg_payload_limit = seg_base_off + seg_cap - 8;
        let mut healed_words = 0usize;

        let word0 = read_header_word64_be(seg_buf, seg_base_off, 8, 0);
        let syn0 = seg_buf[seg_payload_limit - 1];
        let exp_syn0 = compute_hamming_secded8(word0);
        let diff0 = exp_syn0 ^ syn0;
        if diff0 != 0 {
            let err_syn7 = diff0 & 0x7F;
            let parity =
                (word0.count_ones() ^ (syn0 & 0x7F).count_ones() ^ ((syn0 >> 7) as u32 & 1)) & 1;
            if parity == 1 {
                if err_syn7.count_ones() <= 1 {
                    seg_buf[seg_payload_limit - 1] = exp_syn0;
                } else {
                    let bit = secded_tables().syndrome_to_bit[err_syn7 as usize];
                    if bit < 0 {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T5: Unrecoverable DED in dictionary word 0".to_string(),
                        ));
                    }
                    let healed0 = word0 ^ (1u64 << (bit as u32));
                    write_header_word64_be(seg_buf, seg_base_off, 8, 0, healed0);
                    seg_buf[seg_payload_limit - 1] = compute_hamming_secded8(healed0);
                    healed_words += 1;
                }
            } else {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Unrecoverable double-bit error (DED) in dictionary word 0".to_string(),
                ));
            }
        }

        let used_payload_bytes = read_u16_be(seg_buf, seg_base_off + 6) as usize;
        let active_bytes = 8 + used_payload_bytes;
        let active_words = (active_bytes + 7) >> 3;
        if seg_base_off + active_bytes + active_words > seg_payload_limit {
            return Err(LcaError::CorruptRegionEntry(
                "CBIT T5: Dictionary active payload overlaps SEC-DED syndromes".to_string(),
            ));
        }

        for w in 1..active_words {
            let word_w = read_header_word64_be(seg_buf, seg_base_off, active_bytes, w);
            let syn_pos = seg_payload_limit - 1 - w;
            let syn_w = seg_buf[syn_pos];
            let exp_syn_w = compute_hamming_secded8(word_w);
            let diff_w = exp_syn_w ^ syn_w;
            if diff_w == 0 {
                continue;
            }
            let err_syn7 = diff_w & 0x7F;
            let parity =
                (word_w.count_ones() ^ (syn_w & 0x7F).count_ones() ^ ((syn_w >> 7) as u32 & 1)) & 1;
            if parity == 1 {
                if err_syn7.count_ones() <= 1 {
                    seg_buf[syn_pos] = exp_syn_w;
                    continue;
                }
                let bit = secded_tables().syndrome_to_bit[err_syn7 as usize];
                if bit >= 0 {
                    let healed_w = word_w ^ (1u64 << (bit as u32));
                    write_header_word64_be(seg_buf, seg_base_off, active_bytes, w, healed_w);
                    seg_buf[syn_pos] = compute_hamming_secded8(healed_w);
                    healed_words += 1;
                    continue;
                }
            }
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T5: Unrecoverable double-bit error (DED) in dictionary word {}",
                w
            )));
        }

        Ok(healed_words)
    }

    pub fn unpack_nibbles_to_lens(src: &[u8], src_off: usize, out_lens: &mut [u8; 256]) {
        for i in 0..128 {
            let b = src[src_off + i];
            let l0 = (b >> 4) & 0x0F;
            let l1 = b & 0x0F;
            out_lens[i << 1] = if (1..=12).contains(&l0) { l0 } else { 8 };
            out_lens[(i << 1) | 1] = if (1..=12).contains(&l1) { l1 } else { 8 };
        }
    }

    pub fn build_canonical_codes_from_lens(lens: &[u8; 256], out_codes: &mut [u16; 256]) {
        let mut bl_count = [0u16; 13];
        for &l in lens.iter() {
            if (1..=12).contains(&l) {
                bl_count[l as usize] += 1;
            }
        }
        let mut next_code = [0u16; 13];
        let mut code = 0u16;
        for bits in 1..=12 {
            code = (code + bl_count[bits - 1]) << 1;
            next_code[bits] = code;
        }
        for s in 0..256 {
            let l = lens[s] as usize;
            out_codes[s] = next_code[l];
            next_code[l] += 1;
        }
    }

    pub fn build_huff_dec_table12(arena: &[u8], offset: usize) -> [u16; 4096] {
        let mut table = [0u16; 4096];
        if arena.len() < offset + 128 {
            for i in 0..4096 {
                table[i] = (((i >> 4) & 0xFF) << 8) as u16 | 8;
            }
            return table;
        }
        let mut lens = [0u8; 256];
        let mut codes = [0u16; 256];
        unpack_nibbles_to_lens(arena, offset, &mut lens);
        build_canonical_codes_from_lens(&lens, &mut codes);
        for s in 0..256 {
            let l = lens[s] as usize;
            let c = codes[s] as usize;
            let base = (c << (12 - l)) & 0x0FFF;
            let span = 1usize << (12 - l);
            let entry = ((s as u16) << 8) | (l as u16);
            let end = (base + span).min(4096);
            for slot in &mut table[base..end] {
                *slot = entry;
            }
        }
        table
    }

    #[derive(Debug, Clone)]
    pub struct ChainedRegionDictionary {
        pub canonical_states: Vec<String>,
        pub base_block_names: Vec<String>,
        pub biomes: Vec<String>,
        pub dict_pattern_arena: Vec<u8>,
        pub segments: Vec<(usize, usize)>,
        pub healed_words: usize,
        pub lit_huff_table: [u16; 4096],
        pub cmd_huff_table: [u16; 4096],
        pub off_huff_table: [u16; 4096],
    }

    impl ChainedRegionDictionary {
        pub fn parse(region_bytes: &mut [u8], sb: &Superblock) -> LcaResult<Self> {
            let mut canonical_states = Vec::new();
            let mut base_block_names = Vec::new();
            let mut biomes = Vec::new();
            let mut dict_pattern_arena = Vec::new();
            let mut segments = Vec::new();
            let mut healed_words = 0usize;

            let mut seg_offset = sb.dict_root_offset;
            let mut seg_cap = sb.dict_root_capacity;
            let file_payload_limit = region_bytes.len().saturating_sub(FOOTER_SIZE);

            for _hop in 0..64 {
                if seg_cap < 16 || seg_offset + seg_cap > file_payload_limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Dictionary segment out of bounds".to_string(),
                    ));
                }
                segments.push((seg_offset, seg_cap));
                let seg_payload_limit = seg_offset + seg_cap - 8;
                let trailer = read_u64_be(region_bytes, seg_payload_limit);
                if (trailer & 2) != 0 {
                    healed_words += verify_and_heal_dict_segment_secded(region_bytes, seg_offset, seg_cap)?;
                }

                let seg_state_count = read_u32_be(region_bytes, seg_offset) as usize;
                let seg_biome_count = read_u16_be(region_bytes, seg_offset + 4) as usize;
                let used_payload_bytes = read_u16_be(region_bytes, seg_offset + 6) as usize;
                let active_end = seg_offset + 8 + used_payload_bytes;
                if active_end > seg_payload_limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Dictionary segment usedPayloadBytes exceeds segment capacity".to_string(),
                    ));
                }

                let mut pos = seg_offset + 8;
                for _ in 0..seg_state_count {
                    let (utf_len, vlen) = read_varint(region_bytes, pos, active_end)?;
                    pos += vlen;
                    if pos + utf_len + 4 > active_end {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T5: Truncated dictionary BlockState entry".to_string(),
                        ));
                    }
                    let s = std::str::from_utf8(&region_bytes[pos..pos + utf_len]).map_err(|_| {
                        LcaError::CorruptRegionEntry("CBIT T5: Invalid UTF-8 in BlockState dictionary".to_string())
                    })?;
                    let canonical = s.to_string();
                    let base = strip_block_properties(&canonical).to_string();
                    canonical_states.push(canonical);
                    base_block_names.push(base);
                    pos += utf_len + 4;
                }

                for _ in 0..seg_biome_count {
                    let (utf_len, vlen) = read_varint(region_bytes, pos, active_end)?;
                    pos += vlen;
                    if pos + utf_len + 4 > active_end {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T5: Truncated dictionary Biome entry".to_string(),
                        ));
                    }
                    let b = std::str::from_utf8(&region_bytes[pos..pos + utf_len]).map_err(|_| {
                        LcaError::CorruptRegionEntry("CBIT T5: Invalid UTF-8 in Biome dictionary".to_string())
                    })?;
                    biomes.push(b.to_string());
                    pos += utf_len + 4;
                }

                if pos < active_end {
                    dict_pattern_arena.extend_from_slice(&region_bytes[pos..active_end]);
                }

                if (trailer & 1) == 0 {
                    break;
                }
                let rel_slabs32 = (trailer >> 32) as i32;
                let next_cap_slabs24 = ((trailer >> 8) & 0xFF_FFFF) as usize;
                let cur_slab = (seg_offset >> SLAB_SHIFT) as i64;
                let next_slab = cur_slab + (rel_slabs32 as i64);
                if next_slab <= 0 || next_cap_slabs24 == 0 {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Invalid dictionary continuation link trailer".to_string(),
                    ));
                }
                seg_offset = (next_slab as usize) << SLAB_SHIFT;
                seg_cap = next_cap_slabs24 << SLAB_SHIFT;
            }

            let lit_huff_table = build_huff_dec_table12(&dict_pattern_arena, 0);
            let cmd_huff_table = build_huff_dec_table12(&dict_pattern_arena, 128);
            let off_huff_table = build_huff_dec_table12(&dict_pattern_arena, 256);

            Ok(Self {
                canonical_states,
                base_block_names,
                biomes,
                dict_pattern_arena,
                segments,
                healed_words,
                lit_huff_table,
                cmd_huff_table,
                off_huff_table,
            })
        }

        pub fn parse_strict(region_bytes: &[u8], sb: &Superblock) -> LcaResult<Self> {
            let mut canonical_states = Vec::new();
            let mut base_block_names = Vec::new();
            let mut biomes = Vec::new();
            let mut dict_pattern_arena = Vec::new();
            let mut segments = Vec::new();

            let mut seg_offset = sb.dict_root_offset;
            let mut seg_cap = sb.dict_root_capacity;
            let file_payload_limit = region_bytes.len().saturating_sub(FOOTER_SIZE);

            for _hop in 0..64 {
                if seg_cap < 16 || seg_offset + seg_cap > file_payload_limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Dictionary segment out of bounds".to_string(),
                    ));
                }
                segments.push((seg_offset, seg_cap));
                let seg_payload_limit = seg_offset + seg_cap - 8;
                let trailer = read_u64_be(region_bytes, seg_payload_limit);
                let seg_state_count = read_u32_be(region_bytes, seg_offset) as usize;
                let seg_biome_count = read_u16_be(region_bytes, seg_offset + 4) as usize;
                let used_payload_bytes = read_u16_be(region_bytes, seg_offset + 6) as usize;
                let active_end = seg_offset + 8 + used_payload_bytes;
                if active_end > seg_payload_limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Dictionary segment usedPayloadBytes exceeds segment capacity"
                            .to_string(),
                    ));
                }
                if (trailer & 2) != 0 {
                    let active_len = 8 + used_payload_bytes;
                    let word_count = (active_len + 7) >> 3;
                    let syn_ceil = seg_offset + seg_cap - 8;
                    if active_end + word_count > syn_ceil {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T5: Dictionary SEC-DED syndromes overlap payload".to_string(),
                        ));
                    }
                    let seg_slice = &region_bytes[seg_offset..seg_offset + seg_cap];
                    for w in 0..word_count {
                        let word64 = read_header_word64_be(seg_slice, 0, active_len, w);
                        let syn8 = region_bytes[syn_ceil - 1 - w];
                        if compute_hamming_secded8(word64) != syn8 {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT: Dictionary required SEC-DED healing in strict mode"
                                    .to_string(),
                            ));
                        }
                    }
                }

                let mut pos = seg_offset + 8;
                for _ in 0..seg_state_count {
                    let (utf_len, vlen) = read_varint(region_bytes, pos, active_end)?;
                    pos += vlen;
                    if pos + utf_len + 4 > active_end {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T5: Truncated dictionary BlockState entry".to_string(),
                        ));
                    }
                    let s = std::str::from_utf8(&region_bytes[pos..pos + utf_len]).map_err(|_| {
                        LcaError::CorruptRegionEntry(
                            "CBIT T5: Invalid UTF-8 in BlockState dictionary".to_string(),
                        )
                    })?;
                    let canonical = s.to_string();
                    let base = strip_block_properties(&canonical).to_string();
                    canonical_states.push(canonical);
                    base_block_names.push(base);
                    pos += utf_len + 4;
                }

                for _ in 0..seg_biome_count {
                    let (utf_len, vlen) = read_varint(region_bytes, pos, active_end)?;
                    pos += vlen;
                    if pos + utf_len + 4 > active_end {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T5: Truncated dictionary Biome entry".to_string(),
                        ));
                    }
                    let b = std::str::from_utf8(&region_bytes[pos..pos + utf_len]).map_err(|_| {
                        LcaError::CorruptRegionEntry(
                            "CBIT T5: Invalid UTF-8 in Biome dictionary".to_string(),
                        )
                    })?;
                    biomes.push(b.to_string());
                    pos += utf_len + 4;
                }

                if pos < active_end {
                    dict_pattern_arena.extend_from_slice(&region_bytes[pos..active_end]);
                }

                if (trailer & 1) == 0 {
                    break;
                }
                let rel_slabs32 = (trailer >> 32) as i32;
                let next_cap_slabs24 = ((trailer >> 8) & 0xFF_FFFF) as usize;
                let cur_slab = (seg_offset >> SLAB_SHIFT) as i64;
                let next_slab = cur_slab + (rel_slabs32 as i64);
                if next_slab <= 0 || next_cap_slabs24 == 0 {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Invalid dictionary continuation link trailer".to_string(),
                    ));
                }
                seg_offset = (next_slab as usize) << SLAB_SHIFT;
                seg_cap = next_cap_slabs24 << SLAB_SHIFT;
            }

            let lit_huff_table = build_huff_dec_table12(&dict_pattern_arena, 0);
            let cmd_huff_table = build_huff_dec_table12(&dict_pattern_arena, 128);
            let off_huff_table = build_huff_dec_table12(&dict_pattern_arena, 256);

            Ok(Self {
                canonical_states,
                base_block_names,
                biomes,
                dict_pattern_arena,
                segments,
                healed_words: 0,
                lit_huff_table,
                cmd_huff_table,
                off_huff_table,
            })
        }
    }
}

/// Layer 3: `ChunkFrame` Header, Slab-Local Micro-Palette, Mode B (`LCD2` `0x4C44`) Unwrapper,
/// and Pre-Section `ChunkPatchTail` (`0x4000`) Resolution (`LCA-ICD-1.0` Sections 6 & 8).
pub mod layer3 {
    use super::layer1::{compute_hamming_secded8, heal_single_bit_flip64};
    use super::layer2::{
        read_header_word64_be, strip_block_properties, write_header_word64_be, ChainedRegionDictionary,
        STATIC_CORE_BIOMES, STATIC_CORE_PROP_SUFFIXES, STATIC_CORE_STATES,
    };
    use super::layer4::decode_section_biome_payload;
    use super::*;

    pub const BP_FLAG_HAS_CHUNK_PATCH_TAIL: u16 = 0x4000;
    pub const PATCH_FLAG_VOXEL_DELTAS: u8 = 0x01;
    pub const PATCH_FLAG_REPLACEMENT_OFFSET: u8 = 0x02;
    pub const PATCH_FLAG_SKY_LIGHT: u8 = 0x04;
    pub const PATCH_FLAG_BLOCK_LIGHT: u8 = 0x08;

    #[derive(Debug, Clone)]
    pub struct BiomeTemplate {
        pub palette: Vec<String>,
        pub cells_yzx: [usize; BIOME_CELLS],
    }

    #[derive(Debug, Clone)]
    pub struct SlabLocalPalette {
        pub canonical_states: Vec<String>,
        pub base_block_names: Vec<String>,
        pub biomes: Vec<String>,
        pub biome_templates: Vec<BiomeTemplate>,
    }

    #[derive(Debug, Clone)]
    pub struct SectionPatch {
        pub section_index: usize,
        pub patch_flags: u8,
        pub replacement_section_entry16: u16,
        pub yzx_coords: Vec<u16>,
        pub dict_ids: Vec<usize>,
        pub sky_light_2048: Option<Vec<u8>>,
        pub block_light_2048: Option<Vec<u8>>,
    }

    pub fn verify_and_heal_chunk_header_secded(
        slab: &mut [u8],
        slab_offset: usize,
        header_len: usize,
        syndrome_offset: usize,
    ) -> LcaResult<usize> {
        let header_words = (header_len + 7) >> 3;
        if syndrome_offset + header_words > slab.len() || slab_offset + header_len > slab.len() {
            return Err(LcaError::CorruptRegionEntry(
                "CBIT T3c: Mode B header SEC-DED out of bounds".to_string(),
            ));
        }
        let mut healed_words = 0usize;
        for w in 0..header_words {
            let word64 = read_header_word64_be(slab, slab_offset, header_len, w);
            let syn8 = slab[syndrome_offset + w];
            match heal_single_bit_flip64(word64, syn8) {
                Some((healed, _)) => {
                    if healed != word64 {
                        write_header_word64_be(slab, slab_offset, header_len, w, healed);
                        slab[syndrome_offset + w] = compute_hamming_secded8(healed);
                        healed_words += 1;
                    }
                }
                None => {
                    return Err(LcaError::CorruptRegionEntry(format!(
                        "CBIT T3c: Unrecoverable double-bit error (DED) in Mode B header word {}",
                        w
                    )));
                }
            }
        }
        Ok(healed_words)
    }

    pub fn decode_huffman_stream12(
        src: &[u8],
        src_off: usize,
        comp_len: usize,
        dec_table: &[u16; 4096],
        out_len: usize,
    ) -> LcaResult<Vec<u8>> {
        let bit_limit = src_off + comp_len;
        if bit_limit > src.len() {
            return Err(LcaError::CorruptRegionEntry(
                "CBIT T5: Truncated LCD2 Huffman stream".to_string(),
            ));
        }
        let mut dst = vec![0u8; out_len];
        let mut bit_pos = src_off;
        let mut bit_buf = 0u64;
        let mut bits_in_buf = 0usize;
        let mut out_idx = 0usize;

        while out_idx < out_len {
            if bits_in_buf <= 32 {
                if bit_pos + 4 <= bit_limit {
                    let next32 = read_u32_be(src, bit_pos) as u64;
                    bit_buf |= next32 << (32 - bits_in_buf);
                    bits_in_buf += 32;
                    bit_pos += 4;
                } else {
                    while bits_in_buf <= 56 && bit_pos < bit_limit {
                        bit_buf |= (src[bit_pos] as u64) << (56 - bits_in_buf);
                        bit_pos += 1;
                        bits_in_buf += 8;
                    }
                }
            }
            let entry = dec_table[(bit_buf >> 52) as usize];
            dst[out_idx] = (entry >> 8) as u8;
            out_idx += 1;
            let code_len = (entry & 0xFF) as usize;
            if code_len == 0 || code_len > bits_in_buf {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Bitstream underrun in LCD2 Huffman stream".to_string(),
                ));
            }
            bit_buf <<= code_len;
            bits_in_buf -= code_len;
        }
        Ok(dst)
    }

    /// Unpacks a Mode B (`LCD2` `0x4C44` `"LD"`) `ChunkFrame` into a contiguous uncompressed `ChunkFrame`.
    pub fn decode_mode_b_to_full_frame(
        src: &[u8],
        header_start: usize,
        header_len: usize,
        comp_start: usize,
        avail_len: usize,
        dict: &ChainedRegionDictionary,
    ) -> LcaResult<Vec<u8>> {
        let src_limit = comp_start + avail_len;
        if avail_len < 9 || src_limit > src.len() {
            return Err(LcaError::CorruptRegionEntry(
                "CBIT T8: Truncated Mode B LCD2 header".to_string(),
            ));
        }
        let magic = read_u16_be(src, comp_start);
        if magic != LCA_DICT_FRAME_MAGIC {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T8: Invalid Mode B LCD2 magic 0x{:04x} (expected 0x4C44)",
                magic
            )));
        }
        let frame_flags = src[comp_start + 2];
        let comp_body_len = ((src[comp_start + 3] as usize) << 16)
            | ((src[comp_start + 4] as usize) << 8)
            | (src[comp_start + 5] as usize);
        let uncomp_body_len = ((src[comp_start + 6] as usize) << 16)
            | ((src[comp_start + 7] as usize) << 8)
            | (src[comp_start + 8] as usize);
        if comp_body_len < 9
            || comp_start + comp_body_len > src_limit
            || uncomp_body_len > MAX_CHUNK_UNCOMPRESSED_BYTES
        {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T4/T5: Invalid LCD2 frame lengths comp={} uncomp={}",
                comp_body_len, uncomp_body_len
            )));
        }
        if (frame_flags & 0x04) != 0 {
            if 9 + uncomp_body_len > comp_body_len {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Truncated LCD2 raw passthrough body".to_string(),
                ));
            }
            let mut raw_full = Vec::with_capacity(header_len + uncomp_body_len);
            raw_full.extend_from_slice(&src[header_start..header_start + header_len]);
            raw_full.extend_from_slice(&src[comp_start + 9..comp_start + 9 + uncomp_body_len]);
            return Ok(raw_full);
        }

        let limit = comp_start + comp_body_len;
        let mut pos = comp_start + 9;
        let (seq_count, v1) = read_varint(src, pos, limit)?;
        pos += v1;
        let (lit_total, v2) = read_varint(src, pos, limit)?;
        pos += v2;
        let (off_total, v3) = read_varint(src, pos, limit)?;
        pos += v3;
        if lit_total > uncomp_body_len || seq_count > uncomp_body_len {
            return Err(LcaError::CorruptRegionEntry(
                "CBIT T5: Invalid LCD2 stream counts".to_string(),
            ));
        }

        let lit_vec: Vec<u8>;
        let lit_slice: &[u8] = if (frame_flags & 0x01) != 0 {
            let (comp_lit_len, vl) = read_varint(src, pos, limit)?;
            pos += vl;
            if pos + comp_lit_len > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Invalid LCD2 Huffman literal stream".to_string(),
                ));
            }
            lit_vec = decode_huffman_stream12(src, pos, comp_lit_len, &dict.lit_huff_table, lit_total)?;
            pos += comp_lit_len;
            &lit_vec
        } else {
            if pos + lit_total > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Truncated LCD2 literal stream".to_string(),
                ));
            }
            let s = &src[pos..pos + lit_total];
            pos += lit_total;
            s
        };

        let cmd_vec: Vec<u8>;
        let cmd_slice: &[u8] = if (frame_flags & 0x02) != 0 {
            let (comp_cmd_len, vc) = read_varint(src, pos, limit)?;
            pos += vc;
            if pos + comp_cmd_len > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Invalid LCD2 Huffman command stream".to_string(),
                ));
            }
            cmd_vec = decode_huffman_stream12(src, pos, comp_cmd_len, &dict.cmd_huff_table, seq_count)?;
            pos += comp_cmd_len;
            &cmd_vec
        } else {
            if pos + seq_count > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Truncated LCD2 command stream".to_string(),
                ));
            }
            let s = &src[pos..pos + seq_count];
            pos += seq_count;
            s
        };

        let off_vec: Vec<u8>;
        let off_slice: &[u8] = if (frame_flags & 0x08) != 0 {
            let (comp_off_len, vo) = read_varint(src, pos, limit)?;
            pos += vo;
            if pos + comp_off_len > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Invalid LCD2 Huffman offset stream".to_string(),
                ));
            }
            off_vec = decode_huffman_stream12(src, pos, comp_off_len, &dict.off_huff_table, off_total)?;
            &off_vec
        } else {
            if pos + off_total > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Truncated LCD2 offset stream".to_string(),
                ));
            }
            &src[pos..pos + off_total]
        };

        let total_out_len = header_len + uncomp_body_len;
        let mut dst = vec![0u8; total_out_len];
        dst[..header_len].copy_from_slice(&src[header_start..header_start + header_len]);
        let mut dst_pos = header_len;
        let mut lit_pos = 0usize;
        let mut off_pos = 0usize;
        let off_limit = off_slice.len();
        let mut last_offset = 0usize;
        let mut last_is_dict = false;

        for s in 0..seq_count {
            let cmd = cmd_slice[s] as usize;
            let mut lit_len = (cmd >> 5) & 7;
            if lit_len == 7 {
                let (extra, ve) = read_varint(off_slice, off_pos, off_limit)?;
                off_pos += ve;
                lit_len += extra;
            }
            if lit_len > 0 {
                if lit_pos + lit_len > lit_slice.len() || dst_pos + lit_len > total_out_len {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: LCD2 literal copy out of bounds".to_string(),
                    ));
                }
                dst[dst_pos..dst_pos + lit_len].copy_from_slice(&lit_slice[lit_pos..lit_pos + lit_len]);
                lit_pos += lit_len;
                dst_pos += lit_len;
            }

            let match_mode = (cmd >> 3) & 3;
            let min_match = if match_mode == 0 || match_mode == 3 { 3 } else { 4 };
            let mut match_len = (cmd & 7) + min_match;
            if (cmd & 7) == 7 {
                let (extra, ve) = read_varint(off_slice, off_pos, off_limit)?;
                off_pos += ve;
                match_len += extra;
            }

            if match_mode == 0 {
                if off_pos >= off_limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Truncated LCD2 short offset".to_string(),
                    ));
                }
                last_offset = (off_slice[off_pos] as usize) + 1;
                off_pos += 1;
                last_is_dict = false;
            } else if match_mode == 1 {
                if off_pos + 2 > off_limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Truncated LCD2 far offset".to_string(),
                    ));
                }
                last_offset = (((off_slice[off_pos] as usize) << 8) | (off_slice[off_pos + 1] as usize)) + 257;
                off_pos += 2;
                last_is_dict = false;
            } else if match_mode == 2 {
                if off_pos + 2 > off_limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Truncated LCD2 dict offset".to_string(),
                    ));
                }
                last_offset = (((off_slice[off_pos] as usize) << 8) | (off_slice[off_pos + 1] as usize))
                    + DICT_HUFF_HEADER_BYTES;
                off_pos += 2;
                last_is_dict = true;
            }

            if dst_pos + match_len > total_out_len {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: LCD2 match exceeds uncompressed length".to_string(),
                ));
            }
            if last_is_dict {
                if last_offset < DICT_HUFF_HEADER_BYTES
                    || last_offset + match_len > dict.dict_pattern_arena.len()
                {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: LCD2 dictionary offset out of bounds".to_string(),
                    ));
                }
                dst[dst_pos..dst_pos + match_len]
                    .copy_from_slice(&dict.dict_pattern_arena[last_offset..last_offset + match_len]);
                dst_pos += match_len;
            } else {
                if last_offset == 0 || dst_pos < header_len + last_offset {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: LCD2 intra-chunk offset out of bounds".to_string(),
                    ));
                }
                let match_src = dst_pos - last_offset;
                for k in 0..match_len {
                    dst[dst_pos + k] = dst[match_src + k];
                }
                dst_pos += match_len;
            }
        }

        let rem_lit = lit_slice.len().saturating_sub(lit_pos);
        if rem_lit > 0 {
            if dst_pos + rem_lit > total_out_len {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: LCD2 trailing literals exceed uncompressed length".to_string(),
                ));
            }
            dst[dst_pos..dst_pos + rem_lit].copy_from_slice(&lit_slice[lit_pos..lit_pos + rem_lit]);
            dst_pos += rem_lit;
        }
        if dst_pos != total_out_len {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T5: LCD2 decoded length mismatch ({} != {})",
                dst_pos - header_len,
                uncomp_body_len
            )));
        }
        Ok(dst)
    }

    pub fn find_first_section_rel_start(rel_starts: &[isize], residual_rel_start: usize) -> usize {
        let mut min_start = residual_rel_start;
        for &rk in rel_starts {
            if rk >= 0 && (rk as usize) < min_start {
                min_start = rk as usize;
            }
        }
        min_start
    }

    pub fn find_section_rel_end(
        rel_starts: &[isize],
        idx: usize,
        residual_rel_start: usize,
        frame_len: usize,
    ) -> usize {
        let start = rel_starts[idx] as usize;
        let mut best_end = if residual_rel_start > start && residual_rel_start < frame_len {
            residual_rel_start
        } else {
            frame_len
        };
        for &rj in rel_starts {
            if rj >= 0 {
                let rju = rj as usize;
                if rju > start && rju < best_end {
                    best_end = rju;
                }
            }
        }
        best_end
    }

    pub fn find_residual_rel_end(
        rel_starts: &[isize],
        residual_rel_start: usize,
        frame_len: usize,
    ) -> usize {
        let mut best_end = frame_len;
        for &rj in rel_starts {
            if rj >= 0 {
                let rju = rj as usize;
                if rju > residual_rel_start && rju < best_end {
                    best_end = rju;
                }
            }
        }
        best_end
    }

    pub fn read_slab_local_entry_list(
        frame: &[u8],
        mut pos: usize,
        limit: usize,
        count: usize,
        core_names: &[&str],
        strip_core_props: bool,
        names_out: &mut Vec<String>,
    ) -> LcaResult<usize> {
        for _ in 0..count {
            if pos >= limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Truncated slab-local palette".to_string(),
                ));
            }
            let tag = frame[pos] as usize;
            pos += 1;
            if (tag & 0x80) != 0 || (0x60..0x80).contains(&tag) {
                let core_id = if (tag & 0x80) != 0 {
                    tag & 0x7F
                } else {
                    128 + (tag & 0x1F)
                };
                if core_id >= core_names.len() {
                    return Err(LcaError::CorruptRegionEntry(format!(
                        "CBIT T5: Invalid static core token {}",
                        core_id
                    )));
                }
                let entry = if strip_core_props {
                    strip_block_properties(core_names[core_id]).to_string()
                } else {
                    core_names[core_id].to_string()
                };
                names_out.push(entry);
            } else {
                let (utf_len, vlen) = read_varint(frame, pos, limit)?;
                pos += vlen;
                if pos + utf_len > limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T5: Truncated slab-local string".to_string(),
                    ));
                }
                let raw = std::str::from_utf8(&frame[pos..pos + utf_len]).map_err(|_| {
                    LcaError::CorruptRegionEntry("CBIT T5: Invalid UTF-8 in slab-local palette".to_string())
                })?;
                pos += utf_len;
                let full = if tag == 0x40 {
                    format!("minecraft:{}", raw)
                } else {
                    raw.to_string()
                };
                names_out.push(full);
            }
        }
        Ok(pos)
    }

    pub fn decode_slab_local_palette(
        frame: &[u8],
        start_rel: usize,
        limit_rel: usize,
        region_canonical_states: &[String],
    ) -> LcaResult<SlabLocalPalette> {
        let mut pos = start_rel;
        let limit = limit_rel.min(frame.len());
        let (state_count, v1) = read_varint(frame, pos, limit)?;
        pos += v1;
        let mut base_block_names = Vec::with_capacity(state_count);
        pos = read_slab_local_entry_list(
            frame,
            pos,
            limit,
            state_count,
            &STATIC_CORE_STATES,
            true,
            &mut base_block_names,
        )?;

        let (biome_count, v2) = read_varint(frame, pos, limit)?;
        pos += v2;
        let mut biomes = Vec::with_capacity(biome_count);
        pos = read_slab_local_entry_list(
            frame,
            pos,
            limit,
            biome_count,
            &STATIC_CORE_BIOMES,
            false,
            &mut biomes,
        )?;

        let (tmpl_count, v3) = read_varint(frame, pos, limit)?;
        pos += v3;
        let mut biome_templates = Vec::with_capacity(tmpl_count);
        for _ in 0..tmpl_count {
            let (b_len, vb) = read_varint(frame, pos, limit)?;
            pos += vb;
            if b_len == 1 {
                let (b_dict_id, vd) = read_varint(frame, pos, limit)?;
                pos += vd;
                if b_dict_id >= biomes.len() {
                    return Err(LcaError::CorruptRegionEntry(format!(
                        "CBIT T7: Biome dictId {} out of range",
                        b_dict_id
                    )));
                }
                biome_templates.push(BiomeTemplate {
                    palette: vec![biomes[b_dict_id].clone()],
                    cells_yzx: [0usize; BIOME_CELLS],
                });
            } else {
                let mut tmpl_pal = Vec::with_capacity(b_len);
                let mut cells_yzx = [0usize; BIOME_CELLS];
                pos = decode_section_biome_payload(
                    frame,
                    pos,
                    limit,
                    b_len,
                    &biomes,
                    &mut tmpl_pal,
                    &mut cells_yzx,
                )?;
                biome_templates.push(BiomeTemplate {
                    palette: tmpl_pal,
                    cells_yzx,
                });
            }
        }

        let mut canonical_states = base_block_names.clone();
        let (prop_count, vp) = read_varint(frame, pos, limit)?;
        pos += vp;
        for _ in 0..prop_count {
            let (state_idx, vs) = read_varint(frame, pos, limit)?;
            pos += vs;
            if state_idx >= state_count || pos >= limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: Invalid slab-local property suffix index".to_string(),
                ));
            }
            let tag = frame[pos] as usize;
            pos += 1;
            if (tag & 0x80) != 0 {
                let prop_id = tag & 0x7F;
                if prop_id >= STATIC_CORE_PROP_SUFFIXES.len() {
                    return Err(LcaError::CorruptRegionEntry(format!(
                        "CBIT T5: Invalid static property suffix token {}",
                        prop_id
                    )));
                }
                canonical_states[state_idx] =
                    format!("{}{}", base_block_names[state_idx], STATIC_CORE_PROP_SUFFIXES[prop_id]);
            } else {
                let (global_id, vg) = read_varint(frame, pos, limit)?;
                pos += vg;
                if global_id >= region_canonical_states.len() {
                    return Err(LcaError::CorruptRegionEntry(format!(
                        "CBIT T7: Slab-local globalStateId {} out of range",
                        global_id
                    )));
                }
                canonical_states[state_idx] = region_canonical_states[global_id].clone();
            }
        }

        Ok(SlabLocalPalette {
            canonical_states,
            base_block_names,
            biomes,
            biome_templates,
        })
    }

    #[inline]
    pub fn compute_patch_parity8(buf: &[u8], offset: usize, length: usize) -> u8 {
        let mut h: u64 = 0x9E37_79B9_7F4A_7C15 ^ (length as u64);
        for i in 0..length {
            h = (h ^ (buf[offset + i] as u64)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
            h ^= h >> 31;
        }
        h = h.wrapping_mul(0x94D0_49BB_1331_11EB);
        h ^= h >> 33;
        compute_hamming_secded8(h)
    }
}

/// Layer 4: $16 \times 16 \times 16$ `SectionFrame` Codec, 4 Space-Filling Curves (`00:YZX`, `01:H3`,
/// `10:Y_H2`, `11:XZY`), Adaptive Delta-Roaring & Flat Bit-Packed Voxel Containers, $4 \times 4 \times 4$
/// Hilbert Biomes, and 4-Bitplane Gray-Code Lighting (`LCA-ICD-1.0` Section 7).
pub mod layer4 {
    use super::layer3::BiomeTemplate;
    use super::*;

    pub const CURVE_YZX: usize = 0;
    pub const CURVE_H3: usize = 1;
    pub const CURVE_Y_H2: usize = 2;
    pub const CURVE_XZY: usize = 3;

    pub const BLOCK_MODE_UNIFORM: u8 = 0;
    pub const BLOCK_MODE_DELTA_ROARING: u8 = 1;
    pub const BLOCK_MODE_FLAT_BITPACK: u8 = 2;
    pub const BLOCK_MODE_DELTA_ROARING_FULL_MASK: u8 = 3;

    pub const TAG_SINGLETON: usize = 0;
    pub const TAG_DELTA_RUN: usize = 1;
    pub const TAG_DELTA_ARRAY: usize = 2;
    pub const TAG_BITMAP: usize = 3;

    pub const LIGHT_MODE_ABSENT: u8 = 0;
    pub const LIGHT_MODE_CONST: u8 = 1;
    pub const LIGHT_MODE_GRAY_PLANES: u8 = 2;
    pub const LIGHT_MODE_RAW: u8 = 3;

    pub struct CurveTables {
        pub curve_to_yzx: [[usize; SECTION_VOXELS]; 4],
        pub yzx_to_octant6: [usize; SECTION_VOXELS],
    }

    fn xy_to_hilbert2d_16(mut x: i32, mut z: i32) -> usize {
        let mut d = 0i32;
        let mut s = 8i32;
        while s > 0 {
            let rx = if (x & s) != 0 { 1 } else { 0 };
            let rz = if (z & s) != 0 { 1 } else { 0 };
            d += s * s * ((3 * rx) ^ rz);
            if rz == 0 {
                if rx == 1 {
                    x = s - 1 - x;
                    z = s - 1 - z;
                }
                std::mem::swap(&mut x, &mut z);
            }
            s >>= 1;
        }
        d as usize
    }

    fn xy_to_hilbert2d_4(mut x: i32, mut z: i32) -> usize {
        let mut d = 0i32;
        let mut s = 2i32;
        while s > 0 {
            let rx = if (x & s) != 0 { 1 } else { 0 };
            let rz = if (z & s) != 0 { 1 } else { 0 };
            d += s * s * ((3 * rx) ^ rz);
            if rz == 0 {
                if rx == 1 {
                    x = s - 1 - x;
                    z = s - 1 - z;
                }
                std::mem::swap(&mut x, &mut z);
            }
            s >>= 1;
        }
        d as usize
    }

    fn xyz_to_octree_hilbert12(x: usize, y: usize, z: usize) -> usize {
        let oct_x = ((x >> 2) & 3) as i32;
        let oct_y = (y >> 2) & 3;
        let oct_z = ((z >> 2) & 3) as i32;
        let octant6 = (oct_y << 4) | xy_to_hilbert2d_4(oct_x, oct_z);
        let lx = (x & 3) as i32;
        let ly = y & 3;
        let lz = (z & 3) as i32;
        let local6 = (ly << 4) | xy_to_hilbert2d_4(lx, lz);
        (octant6 << 6) | local6
    }

    impl CurveTables {
        pub fn build() -> Self {
            let mut curve_to_yzx = [[0usize; SECTION_VOXELS]; 4];
            let mut yzx_to_octant6 = [0usize; SECTION_VOXELS];
            for y in 0..16 {
                for z in 0..16 {
                    for x in 0..16 {
                        let yzx = (y << 8) | (z << 4) | x;
                        curve_to_yzx[CURVE_YZX][yzx] = yzx;
                        let yh2 = (y << 8) | xy_to_hilbert2d_16(x as i32, z as i32);
                        curve_to_yzx[CURVE_Y_H2][yh2] = yzx;
                        let xzy = (x << 8) | (z << 4) | y;
                        curve_to_yzx[CURVE_XZY][xzy] = yzx;
                        let h3 = xyz_to_octree_hilbert12(x, y, z);
                        curve_to_yzx[CURVE_H3][h3] = yzx;
                        yzx_to_octant6[yzx] = h3 >> 6;
                    }
                }
            }
            Self {
                curve_to_yzx,
                yzx_to_octant6,
            }
        }
    }

    pub fn curve_tables() -> &'static CurveTables {
        use std::sync::OnceLock;
        static TABLES: OnceLock<CurveTables> = OnceLock::new();
        TABLES.get_or_init(CurveTables::build)
    }

    #[derive(Debug, Clone)]
    pub struct DecodedSectionFrame {
        pub section_y: i32,
        pub inline_uniform: bool,
        pub curve_id: usize,
        pub octree_occupancy_mask64: i64,
        pub block_palette: Vec<String>,
        pub yzx_palette_indices: Vec<u16>,
        pub biome_palette: Vec<String>,
        pub biome_cells_yzx: [usize; BIOME_CELLS],
        pub sky_light_2048: Option<Vec<u8>>,
        pub block_light_2048: Option<Vec<u8>>,
    }

    pub fn decode_section_biome_payload(
        buf: &[u8],
        mut pos: usize,
        limit: usize,
        b_len: usize,
        dict_biomes: &[String],
        biome_palette_out: &mut Vec<String>,
        cells_yzx_out: &mut [usize; BIOME_CELLS],
    ) -> LcaResult<usize> {
        if b_len == 0 || b_len > BIOME_CELLS {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T7: Invalid biome palette length {}",
                b_len
            )));
        }
        for _ in 0..b_len {
            let (b_dict_id, vb) = read_varint(buf, pos, limit)?;
            pos += vb;
            if b_dict_id >= dict_biomes.len() {
                return Err(LcaError::CorruptRegionEntry(format!(
                    "CBIT T7: Biome dictId {} out of range {}",
                    b_dict_id,
                    dict_biomes.len()
                )));
            }
            biome_palette_out.push(dict_biomes[b_dict_id].clone());
        }
        cells_yzx_out.fill(0);
        if b_len > 1 {
            let (run_count, vr) = read_varint(buf, pos, limit)?;
            pos += vr;
            let mut cursor = 0usize;
            if b_len <= 8 {
                for _ in 0..run_count {
                    if pos >= limit {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT: Truncated 1B biome RLE".to_string(),
                        ));
                    }
                    let packed = buf[pos] as usize;
                    pos += 1;
                    let p_idx = (packed >> 5) & 7;
                    let len = (packed & 0x1F) + 1;
                    if p_idx >= b_len || cursor + len > BIOME_CELLS {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT: Biome RLE cursor overflow".to_string(),
                        ));
                    }
                    for _ in 0..len {
                        let yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >> 4) & 3);
                        cells_yzx_out[yzx] = p_idx;
                        cursor += 1;
                    }
                }
            } else {
                for _ in 0..run_count {
                    let (p_idx, vp) = read_varint(buf, pos, limit)?;
                    pos += vp;
                    if pos >= limit {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT: Truncated biome RLE".to_string(),
                        ));
                    }
                    let len = (buf[pos] as usize) + 1;
                    pos += 1;
                    if p_idx >= b_len || cursor + len > BIOME_CELLS {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT: Biome RLE cursor overflow".to_string(),
                        ));
                    }
                    for _ in 0..len {
                        let yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >> 4) & 3);
                        cells_yzx_out[yzx] = p_idx;
                        cursor += 1;
                    }
                }
            }
        }
        Ok(pos)
    }

    pub fn decode_gray_planes(
        buf: &[u8],
        mut pos: usize,
        limit: usize,
        curve_to_yzx: &[usize; SECTION_VOXELS],
    ) -> LcaResult<(usize, Vec<u8>)> {
        if pos >= limit {
            return Err(LcaError::CorruptRegionEntry(
                "CBIT: Truncated Gray light planeModes8 header".to_string(),
            ));
        }
        let plane_modes8 = buf[pos];
        pos += 1;
        let mut gray_curve = [0u8; SECTION_VOXELS];
        for plane in 0..4 {
            let mode = (plane_modes8 >> (plane << 1)) & 3;
            let bit = 1u8 << plane;
            let prev_bit = if plane > 0 { 1u8 << (plane - 1) } else { 0 };
            if mode == 0 {
                // All-0
            } else if mode == 1 {
                if pos >= limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT: Truncated Gray light subHdr".to_string(),
                    ));
                }
                let sub_hdr = buf[pos] as usize;
                pos += 1;
                if sub_hdr == 0xFF {
                    for g in &mut gray_curve {
                        *g |= bit;
                    }
                } else if (sub_hdr & 0xC0) == 0xC0 {
                    if pos >= limit {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT: Truncated single-run Gray light plane".to_string(),
                        ));
                    }
                    let kind = (sub_hdr >> 4) & 3;
                    let val12 = ((sub_hdr & 0x0F) << 8) | (buf[pos] as usize);
                    pos += 1;
                    let (start, end) = if kind == 2 {
                        (val12, SECTION_VOXELS)
                    } else if kind == 1 {
                        (0, val12 + 1)
                    } else {
                        let (len_m1, vl) = read_varint(buf, pos, limit)?;
                        pos += vl;
                        (val12, val12 + len_m1 + 1)
                    };
                    if start > end || end > SECTION_VOXELS {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T6: Gray single-run overflow".to_string(),
                        ));
                    }
                    for g in &mut gray_curve[start..end] {
                        *g |= bit;
                    }
                } else {
                    let ref_code = (sub_hdr >> 6) & 3;
                    let split62 = (sub_hdr & 0x20) != 0;
                    let mut run_count = sub_hdr & 0x1F;
                    if run_count == 31 {
                        let (extra, ve) = read_varint(buf, pos, limit)?;
                        pos += ve;
                        run_count += extra;
                    }
                    if ref_code == 2 {
                        for g in &mut gray_curve {
                            *g |= bit;
                        }
                    } else if ref_code == 1 && prev_bit != 0 {
                        for g in &mut gray_curve {
                            if (*g & prev_bit) != 0 {
                                *g |= bit;
                            }
                        }
                    }
                    let l_bits = if split62 { 2 } else { 4 };
                    let g_max = if split62 { 63 } else { 15 };
                    let l_max = if split62 { 3 } else { 15 };
                    let mut cursor = 0usize;
                    for r in 0..run_count {
                        if pos >= limit {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT: Truncated Gray light run".to_string(),
                            ));
                        }
                        let tok = buf[pos] as usize;
                        pos += 1;
                        let mut adj_gap = (tok >> l_bits) & g_max;
                        let mut len_m1 = tok & l_max;
                        if adj_gap == g_max {
                            let (extra, ve) = read_varint(buf, pos, limit)?;
                            pos += ve;
                            adj_gap += extra;
                        }
                        if len_m1 == l_max {
                            let (extra, ve) = read_varint(buf, pos, limit)?;
                            pos += ve;
                            len_m1 += extra;
                        }
                        cursor += if r == 0 { adj_gap } else { adj_gap + 1 };
                        let run_len = len_m1 + 1;
                        if cursor + run_len > SECTION_VOXELS {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Gray light run cursor overflow".to_string(),
                            ));
                        }
                        for _ in 0..run_len {
                            gray_curve[cursor] ^= bit;
                            cursor += 1;
                        }
                    }
                }
            } else if mode == 2 {
                if pos >= limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT: Truncated Gray light mode 2 runCount".to_string(),
                    ));
                }
                let mut run_count = buf[pos] as usize;
                pos += 1;
                if run_count == 255 {
                    let (extra, ve) = read_varint(buf, pos, limit)?;
                    pos += ve;
                    run_count += extra;
                }
                let mut cursor = 0usize;
                for r in 0..run_count {
                    if pos >= limit {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT: Truncated Gray light mode 2 token".to_string(),
                        ));
                    }
                    let tok = buf[pos] as usize;
                    pos += 1;
                    let mut adj_gap = (tok >> 4) & 0x0F;
                    let mut len_m1 = tok & 0x0F;
                    if adj_gap == 15 {
                        let (extra, ve) = read_varint(buf, pos, limit)?;
                        pos += ve;
                        adj_gap += extra;
                    }
                    if len_m1 == 15 {
                        let (extra, ve) = read_varint(buf, pos, limit)?;
                        pos += ve;
                        len_m1 += extra;
                    }
                    cursor += if r == 0 { adj_gap } else { adj_gap + 1 };
                    let run_len = len_m1 + 1;
                    if cursor + run_len > SECTION_VOXELS {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T6: Gray light run cursor overflow".to_string(),
                        ));
                    }
                    for _ in 0..run_len {
                        gray_curve[cursor] |= bit;
                        cursor += 1;
                    }
                }
            } else {
                if pos + 512 > limit {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT: Truncated 512B Gray light bitmap".to_string(),
                    ));
                }
                for b in 0..512 {
                    let mask8 = buf[pos + b];
                    if mask8 == 0 {
                        continue;
                    }
                    let base = b << 3;
                    for k in 0..8 {
                        if (mask8 & (1 << k)) != 0 {
                            gray_curve[base + k] |= bit;
                        }
                    }
                }
                pos += 512;
            }
        }

        let mut nibbles_yzx = [0u8; SECTION_VOXELS];
        for k in 0..SECTION_VOXELS {
            let g = gray_curve[k];
            let mut l = g ^ (g >> 1);
            l ^= l >> 2;
            nibbles_yzx[curve_to_yzx[k]] = l & 0x0F;
        }
        let mut out = vec![0u8; 2048];
        for i in 0..2048 {
            let lo = nibbles_yzx[i << 1] & 0x0F;
            let hi = nibbles_yzx[(i << 1) | 1] & 0x0F;
            out[i] = (hi << 4) | lo;
        }
        Ok((pos, out))
    }

    pub fn create_uniform_decoded_section(
        section_y: i32,
        entry16: u16,
        has_slab_local_palette: bool,
        dict_canonical_states: &[String],
        dict_biomes: &[String],
        biome_templates: &[BiomeTemplate],
        default_biome_dict_id: isize,
    ) -> LcaResult<DecodedSectionFrame> {
        let mut canon_state = "minecraft:air".to_string();
        let mut sky_light_2048 = None;
        let mut block_light_2048 = None;
        let mut biome_palette = vec!["minecraft:plains".to_string()];
        let mut biome_cells_yzx = [0usize; BIOME_CELLS];

        if entry16 != 0 && (entry16 & 0x8000) != 0 {
            let sky_light2 = (entry16 >> 13) & 3;
            let global_dict_id = if has_slab_local_palette {
                (entry16 & 0x07FF) as usize
            } else {
                (entry16 & 0x1FFF) as usize
            };
            if global_dict_id >= dict_canonical_states.len() {
                return Err(LcaError::CorruptRegionEntry(format!(
                    "CBIT T7: Inline globalDictId {} out of bounds {}",
                    global_dict_id,
                    dict_canonical_states.len()
                )));
            }
            canon_state = dict_canonical_states[global_dict_id].clone();
            if sky_light2 == 0b01 || sky_light2 == 0b10 {
                sky_light_2048 = Some(vec![0xFFu8; 2048]);
            }
            if sky_light2 == 0b10 || sky_light2 == 0b11 {
                block_light_2048 = Some(vec![0xFFu8; 2048]);
            }
            if has_slab_local_palette && !biome_templates.is_empty() {
                let tmpl_idx = ((entry16 >> 11) & 3) as usize;
                if tmpl_idx < biome_templates.len() {
                    biome_palette = biome_templates[tmpl_idx].palette.clone();
                    biome_cells_yzx = biome_templates[tmpl_idx].cells_yzx;
                }
            } else if default_biome_dict_id >= 0 && (default_biome_dict_id as usize) < dict_biomes.len() {
                biome_palette = vec![dict_biomes[default_biome_dict_id as usize].clone()];
            }
        } else if default_biome_dict_id >= 0 && (default_biome_dict_id as usize) < dict_biomes.len() {
            biome_palette = vec![dict_biomes[default_biome_dict_id as usize].clone()];
        }

        Ok(DecodedSectionFrame {
            section_y,
            inline_uniform: true,
            curve_id: CURVE_H3,
            octree_occupancy_mask64: 0,
            block_palette: vec![canon_state],
            yzx_palette_indices: Vec::new(),
            biome_palette,
            biome_cells_yzx,
            sky_light_2048,
            block_light_2048,
        })
    }

    pub fn decode_section_frame(
        buf: &[u8],
        offset: usize,
        limit: usize,
        section_y: i32,
        dict_canonical_states: &[String],
        dict_biomes: &[String],
        chunk_biome_templates: &[BiomeTemplate],
        default_biome_dict_id: isize,
    ) -> LcaResult<DecodedSectionFrame> {
        if offset + 2 > limit || limit > buf.len() {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T5: SectionFrame truncated at offset {}",
                offset
            )));
        }
        let mut pos = offset;
        let section_header = buf[pos];
        pos += 1;
        let curve_id = ((section_header >> 6) & 3) as usize;
        let block_mode = (section_header >> 4) & 3;
        let sky_mode = (section_header >> 2) & 3;
        let blk_mode = section_header & 3;

        let octree_mask64: i64 = if block_mode == BLOCK_MODE_UNIFORM {
            0
        } else if block_mode == BLOCK_MODE_DELTA_ROARING_FULL_MASK {
            -1
        } else {
            if pos + 8 > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: SectionFrame truncated reading octreeOccupancyMask64".to_string(),
                ));
            }
            let m = read_u64_be(buf, pos) as i64;
            pos += 8;
            m
        };

        let (palette_len, vp) = read_varint(buf, pos, limit)?;
        pos += vp;
        if palette_len == 0 || palette_len > SECTION_VOXELS {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT T7: Invalid section paletteLen {}",
                palette_len
            )));
        }

        let mut block_palette = Vec::with_capacity(palette_len);
        for _ in 0..palette_len {
            let (dict_id, vd) = read_varint(buf, pos, limit)?;
            pos += vd;
            if dict_id >= dict_canonical_states.len() {
                return Err(LcaError::CorruptRegionEntry(format!(
                    "CBIT T7: Section dictId {} >= totalDictStateCount {}",
                    dict_id,
                    dict_canonical_states.len()
                )));
            }
            block_palette.push(dict_canonical_states[dict_id].clone());
        }

        let tables = curve_tables();
        let curve_to_yzx = &tables.curve_to_yzx[curve_id];
        let yzx_to_octant6 = &tables.yzx_to_octant6;
        let mut yzx_palette_indices = Vec::new();

        if block_mode == BLOCK_MODE_UNIFORM || palette_len == 1 {
            // Uniform section: yzx_palette_indices stays empty (all 0)
        } else if block_mode == BLOCK_MODE_DELTA_ROARING
            || block_mode == BLOCK_MODE_DELTA_ROARING_FULL_MASK
        {
            yzx_palette_indices = vec![0u16; SECTION_VOXELS];
            let mut chunk_base: Option<(Vec<usize>, usize, usize)> = None;
            let mut active_voxels = SECTION_VOXELS;
            let expected_u64 = octree_mask64 as u64;

            if octree_mask64 != -1 && octree_mask64 != 0 {
                if curve_id == CURVE_H3 {
                    let mut bases = Vec::with_capacity(expected_u64.count_ones() as usize);
                    let mut m = expected_u64;
                    while m != 0 {
                        let oct = m.trailing_zeros() as usize;
                        bases.push(oct << 6);
                        m &= m - 1;
                    }
                    active_voxels = bases.len() << 6;
                    chunk_base = Some((bases, 6, 63));
                } else if curve_id == CURVE_YZX || curve_id == CURVE_Y_H2 {
                    let mut bases = Vec::with_capacity(4);
                    for oct_y in 0..4 {
                        if ((expected_u64 >> (oct_y << 4)) & 0xFFFF) != 0 {
                            bases.push(oct_y << 10);
                        }
                    }
                    if bases.len() < 4 {
                        active_voxels = bases.len() << 10;
                        chunk_base = Some((bases, 10, 1023));
                    }
                }
            }

            let map_cursor = |c: usize| -> usize {
                if let Some((ref bases, shift, mask)) = chunk_base {
                    bases[c >> shift] | (c & mask)
                } else {
                    c
                }
            };

            let mut decoded_octree_mask64 = 0u64;
            for p in 1..palette_len {
                if pos + 2 > limit {
                    return Err(LcaError::CorruptRegionEntry(format!(
                        "CBIT T6: Truncated minority container header for p={}",
                        p
                    )));
                }
                let word16 = read_u16_be(buf, pos) as usize;
                pos += 2;
                let tag = (word16 >> 14) & 3;
                let count_or_idx = word16 & 0x3FFF;
                let p_u16 = p as u16;

                if tag == TAG_SINGLETON {
                    let sub_tag = (count_or_idx >> 12) & 3;
                    let idx12 = count_or_idx & 0x0FFF;
                    if sub_tag == 0 {
                        if idx12 >= active_voxels {
                            return Err(LcaError::CorruptRegionEntry(format!(
                                "CBIT T6: Singleton index out of bounds: {}",
                                idx12
                            )));
                        }
                        let h = map_cursor(idx12);
                        let yzx = curve_to_yzx[h];
                        yzx_palette_indices[yzx] = p_u16;
                        decoded_octree_mask64 |= 1u64 << yzx_to_octant6[yzx];
                    } else if sub_tag == 1 || sub_tag == 2 {
                        let run_len = if sub_tag == 1 {
                            if pos >= limit {
                                return Err(LcaError::CorruptRegionEntry(
                                    "CBIT T6: Truncated 1B single-run".to_string(),
                                ));
                            }
                            let v = (buf[pos] as usize) + 2;
                            pos += 1;
                            v
                        } else {
                            if pos + 2 > limit {
                                return Err(LcaError::CorruptRegionEntry(
                                    "CBIT T6: Truncated 2B single-run".to_string(),
                                ));
                            }
                            let v = (read_u16_be(buf, pos) as usize) + 2;
                            pos += 2;
                            v
                        };
                        if idx12 + run_len > active_voxels {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Single-run overflow".to_string(),
                            ));
                        }
                        for c in idx12..idx12 + run_len {
                            let h = map_cursor(c);
                            let yzx = curve_to_yzx[h];
                            yzx_palette_indices[yzx] = p_u16;
                            decoded_octree_mask64 |= 1u64 << yzx_to_octant6[yzx];
                        }
                    } else {
                        if pos + 2 > limit {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Truncated two-run singleton header".to_string(),
                            ));
                        }
                        let lens = buf[pos] as usize;
                        pos += 1;
                        let run_len0 = (lens >> 4) + 1;
                        let run_len1 = (lens & 0x0F) + 1;
                        let mut adj_gap = buf[pos] as usize;
                        pos += 1;
                        if adj_gap >= 240 {
                            if pos >= limit {
                                return Err(LcaError::CorruptRegionEntry(
                                    "CBIT T6: Truncated 2B two-run gap".to_string(),
                                ));
                            }
                            adj_gap = ((adj_gap & 0x0F) << 8) | (buf[pos] as usize);
                            pos += 1;
                        }
                        let end0 = idx12 + run_len0;
                        let start1 = end0 + 1 + adj_gap;
                        let end1 = start1 + run_len1;
                        if end0 > active_voxels || end1 > active_voxels {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Two-run index out of bounds".to_string(),
                            ));
                        }
                        for c in idx12..end0 {
                            let h = map_cursor(c);
                            let yzx = curve_to_yzx[h];
                            yzx_palette_indices[yzx] = p_u16;
                            decoded_octree_mask64 |= 1u64 << yzx_to_octant6[yzx];
                        }
                        for c in start1..end1 {
                            let h = map_cursor(c);
                            let yzx = curve_to_yzx[h];
                            yzx_palette_indices[yzx] = p_u16;
                            decoded_octree_mask64 |= 1u64 << yzx_to_octant6[yzx];
                        }
                    }
                } else if tag == TAG_BITMAP {
                    if count_or_idx == 512 || (count_or_idx & 0x1000) != 0 {
                        let byte_len = if count_or_idx == 512 {
                            512
                        } else {
                            count_or_idx & 0x0FFF
                        };
                        if pos + byte_len > limit
                            || (byte_len & 7) != 0
                            || (byte_len << 3) > active_voxels
                        {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Truncated or invalid TAG_BITMAP container".to_string(),
                            ));
                        }
                        let word_count = byte_len >> 3;
                        for w in 0..word_count {
                            let mut bits64 = read_u64_be(buf, pos);
                            pos += 8;
                            let base_c = w << 6;
                            while bits64 != 0 {
                                let bit = bits64.trailing_zeros() as usize;
                                bits64 &= bits64 - 1;
                                let h = map_cursor(base_c + bit);
                                let yzx = curve_to_yzx[h];
                                yzx_palette_indices[yzx] = p_u16;
                                decoded_octree_mask64 |= 1u64 << yzx_to_octant6[yzx];
                            }
                        }
                    } else {
                        let non_zero_words = count_or_idx;
                        let pay_bytes = 8 + (non_zero_words << 3);
                        if non_zero_words == 0 || non_zero_words > 64 || pos + pay_bytes > limit {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Truncated Word-Masked TAG_BITMAP container".to_string(),
                            ));
                        }
                        let mut word_mask64 = read_u64_be(buf, pos);
                        pos += 8;
                        if word_mask64.count_ones() as usize != non_zero_words {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Word-Masked TAG_BITMAP popcount mismatch".to_string(),
                            ));
                        }
                        let max_words = active_voxels >> 6;
                        while word_mask64 != 0 {
                            let w = word_mask64.trailing_zeros() as usize;
                            word_mask64 &= word_mask64 - 1;
                            if w >= max_words {
                                return Err(LcaError::CorruptRegionEntry(
                                    "CBIT T6: Word-Masked TAG_BITMAP word out of bounds".to_string(),
                                ));
                            }
                            let mut bits64 = read_u64_be(buf, pos);
                            pos += 8;
                            let base_c = w << 6;
                            while bits64 != 0 {
                                let bit = bits64.trailing_zeros() as usize;
                                bits64 &= bits64 - 1;
                                let h = map_cursor(base_c + bit);
                                let yzx = curve_to_yzx[h];
                                yzx_palette_indices[yzx] = p_u16;
                                decoded_octree_mask64 |= 1u64 << yzx_to_octant6[yzx];
                            }
                        }
                    }
                } else if tag == TAG_DELTA_ARRAY {
                    let mut cursor = 0usize;
                    for r in 0..count_or_idx {
                        if pos >= limit {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Truncated TAG_DELTA_ARRAY".to_string(),
                            ));
                        }
                        let mut adj_gap = buf[pos] as usize;
                        pos += 1;
                        if adj_gap >= 240 {
                            if pos >= limit {
                                return Err(LcaError::CorruptRegionEntry(
                                    "CBIT T6: Truncated 2-byte TAG_DELTA_ARRAY gap".to_string(),
                                ));
                            }
                            adj_gap = ((adj_gap & 0x0F) << 8) | (buf[pos] as usize);
                            pos += 1;
                        }
                        cursor += if r == 0 { adj_gap } else { adj_gap + 1 };
                        if cursor >= active_voxels {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: TAG_DELTA_ARRAY cursor overflow".to_string(),
                            ));
                        }
                        let h = map_cursor(cursor);
                        cursor += 1;
                        let yzx = curve_to_yzx[h];
                        yzx_palette_indices[yzx] = p_u16;
                        decoded_octree_mask64 |= 1u64 << yzx_to_octant6[yzx];
                    }
                } else {
                    let sub_mode = (count_or_idx >> 12) & 3;
                    let run_count = count_or_idx & 0x0FFF;
                    let l_bits = 4 - sub_mode;
                    let g_max = (1usize << (4 + sub_mode)) - 1;
                    let l_max = (1usize << l_bits) - 1;
                    let mut cursor = 0usize;
                    for r in 0..run_count {
                        if pos >= limit {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Truncated TAG_DELTA_RUN".to_string(),
                            ));
                        }
                        let tok = buf[pos] as usize;
                        pos += 1;
                        let mut adj_gap = (tok >> l_bits) & g_max;
                        let mut len_m1 = tok & l_max;
                        if adj_gap == g_max {
                            if pos >= limit {
                                return Err(LcaError::CorruptRegionEntry(
                                    "CBIT T6: Truncated bounded gap byte".to_string(),
                                ));
                            }
                            let b0 = buf[pos] as usize;
                            pos += 1;
                            if b0 < 240 {
                                adj_gap += b0;
                            } else {
                                if pos >= limit {
                                    return Err(LcaError::CorruptRegionEntry(
                                        "CBIT T6: Truncated 2-byte bounded gap".to_string(),
                                    ));
                                }
                                adj_gap += ((b0 & 0x0F) << 8) | (buf[pos] as usize);
                                pos += 1;
                            }
                        }
                        if len_m1 == l_max {
                            let (extra, ve) = read_varint(buf, pos, limit)?;
                            pos += ve;
                            len_m1 += extra;
                        }
                        cursor += if r == 0 { adj_gap } else { adj_gap + 1 };
                        let run_len = len_m1 + 1;
                        let run_end = cursor + run_len;
                        if run_end > active_voxels {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T6: Delta-Roaring run cursor exceeded activeVoxels".to_string(),
                            ));
                        }
                        for c in cursor..run_end {
                            let h = map_cursor(c);
                            let yzx = curve_to_yzx[h];
                            yzx_palette_indices[yzx] = p_u16;
                            decoded_octree_mask64 |= 1u64 << yzx_to_octant6[yzx];
                        }
                        cursor = run_end;
                    }
                }
            }

            if decoded_octree_mask64 != expected_u64 {
                return Err(LcaError::CorruptRegionEntry(format!(
                    "CBIT T6b: Section octreeOccupancyMask64 syndrome mismatch (expected=0x{:016x}, actual=0x{:016x})",
                    expected_u64, decoded_octree_mask64
                )));
            }
        } else if block_mode == BLOCK_MODE_FLAT_BITPACK {
            yzx_palette_indices = vec![0u16; SECTION_VOXELS];
            let flat_bits = (32 - ((palette_len - 1) as u32).leading_zeros()).max(1) as usize;
            let flat_byte_count = (SECTION_VOXELS * flat_bits + 7) >> 3;
            if pos + flat_byte_count > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T6: Truncated FlatBitPack section payload".to_string(),
                ));
            }
            let mut decoded_octree_mask64 = 0u64;
            let mut bit_pos = 0usize;
            for i in 0..SECTION_VOXELS {
                let mut val = 0usize;
                for b in 0..flat_bits {
                    if (buf[pos + (bit_pos >> 3)] & (1 << (bit_pos & 7))) != 0 {
                        val |= 1 << b;
                    }
                    bit_pos += 1;
                }
                if val >= palette_len {
                    return Err(LcaError::CorruptRegionEntry(format!(
                        "CBIT T7: FlatBitPack palette index {} >= {}",
                        val, palette_len
                    )));
                }
                yzx_palette_indices[i] = val as u16;
                if val != 0 {
                    decoded_octree_mask64 |= 1u64 << yzx_to_octant6[i];
                }
            }
            if decoded_octree_mask64 != (octree_mask64 as u64) {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T6b: FlatBitPack octreeOccupancyMask64 syndrome mismatch".to_string(),
                ));
            }
            pos += flat_byte_count;
        } else {
            return Err(LcaError::CorruptRegionEntry(format!(
                "CBIT: Unsupported blockEncodingMode {}",
                block_mode
            )));
        }

        let mut biome_palette = Vec::new();
        let mut biome_cells_yzx = [0usize; BIOME_CELLS];
        if pos < limit {
            let first_byte = buf[pos] as usize;
            if (first_byte & 0x80) != 0 {
                pos += 1;
                let tmpl_idx = first_byte & 0x7F;
                if tmpl_idx >= chunk_biome_templates.len() {
                    return Err(LcaError::CorruptRegionEntry(format!(
                        "CBIT T7: Biome template index {} out of bounds",
                        tmpl_idx
                    )));
                }
                biome_palette = chunk_biome_templates[tmpl_idx].palette.clone();
                biome_cells_yzx = chunk_biome_templates[tmpl_idx].cells_yzx;
            } else {
                let (b_len, vb) = read_varint(buf, pos, limit)?;
                pos += vb;
                if b_len > 0 {
                    pos = decode_section_biome_payload(
                        buf,
                        pos,
                        limit,
                        b_len,
                        dict_biomes,
                        &mut biome_palette,
                        &mut biome_cells_yzx,
                    )?;
                }
            }
        }
        if biome_palette.is_empty() {
            if default_biome_dict_id >= 0 && (default_biome_dict_id as usize) < dict_biomes.len() {
                biome_palette.push(dict_biomes[default_biome_dict_id as usize].clone());
            } else {
                biome_palette.push("minecraft:plains".to_string());
            }
        }

        let mut const_nibbles = 0u8;
        if sky_mode == LIGHT_MODE_CONST || blk_mode == LIGHT_MODE_CONST {
            if pos >= limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT: Truncated const light byte in SectionFrame".to_string(),
                ));
            }
            const_nibbles = buf[pos];
            pos += 1;
        }

        let sky_light_2048 = if sky_mode == LIGHT_MODE_ABSENT {
            None
        } else if sky_mode == LIGHT_MODE_CONST {
            let c = const_nibbles & 0x0F;
            Some(vec![(c << 4) | c; 2048])
        } else if sky_mode == LIGHT_MODE_GRAY_PLANES {
            let (next_pos, sky) =
                decode_gray_planes(buf, pos, limit, &tables.curve_to_yzx[CURVE_Y_H2])?;
            pos = next_pos;
            Some(sky)
        } else {
            if pos + 2048 > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT: Truncated raw SkyLight".to_string(),
                ));
            }
            let sky = buf[pos..pos + 2048].to_vec();
            pos += 2048;
            Some(sky)
        };

        let block_light_2048 = if blk_mode == LIGHT_MODE_ABSENT {
            None
        } else if blk_mode == LIGHT_MODE_CONST {
            let c = (const_nibbles >> 4) & 0x0F;
            Some(vec![(c << 4) | c; 2048])
        } else if blk_mode == LIGHT_MODE_GRAY_PLANES {
            let (_next_pos, blk) =
                decode_gray_planes(buf, pos, limit, &tables.curve_to_yzx[CURVE_H3])?;
            Some(blk)
        } else {
            if pos + 2048 > limit {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT: Truncated raw BlockLight".to_string(),
                ));
            }
            Some(buf[pos..pos + 2048].to_vec())
        };

        Ok(DecodedSectionFrame {
            section_y,
            inline_uniform: false,
            curve_id,
            octree_occupancy_mask64: octree_mask64,
            block_palette,
            yzx_palette_indices,
            biome_palette,
            biome_cells_yzx,
            sky_light_2048,
            block_light_2048,
        })
    }
}

/// `CBIT T1..T8` Tripwire Verifier and 4-Tier `SEC-DED` Self-Healing Engine (`leafanvil-ADR-004` Section 2.3).
pub mod cbit {
    use super::layer1::{
        compute_hamming_secded8, heal_single_bit_flip64, parse_and_validate_superblock,
        ChunkPointer64,
    };
    use super::layer2::ChainedRegionDictionary;
    use super::layer3::verify_and_heal_chunk_header_secded;
    use super::*;

    #[derive(Debug, Clone, Default)]
    pub struct RecoveryReport {
        pub secded_healed_pointers: usize,
        pub dict_secded_healed_words: usize,
        pub chunk_header_secded_healed_words: usize,
        pub slab_scan_reconstructed_pointers: usize,
        pub diagnostics: Vec<String>,
    }

    impl RecoveryReport {
        pub fn total_healed(&self) -> usize {
            self.secded_healed_pointers
                + self.dict_secded_healed_words
                + self.chunk_header_secded_healed_words
                + self.slab_scan_reconstructed_pointers
        }
    }

    pub fn probe_looks_like_mode_b(
        buf: &[u8],
        byte_pos: usize,
        header_len: usize,
        header_words: usize,
        avail: usize,
    ) -> bool {
        let comp_rel = header_len + header_words;
        if avail >= comp_rel + 9 && byte_pos + comp_rel + 9 <= buf.len() {
            let magic = read_u16_be(buf, byte_pos + comp_rel);
            if magic == LCA_DICT_FRAME_MAGIC {
                let comp_body_len = ((buf[byte_pos + comp_rel + 3] as usize) << 16)
                    | ((buf[byte_pos + comp_rel + 4] as usize) << 8)
                    | (buf[byte_pos + comp_rel + 5] as usize);
                return comp_body_len >= 9 && comp_rel + comp_body_len <= avail;
            }
        }
        false
    }

    pub fn estimate_self_describing_chunk_len(
        buf: &[u8],
        frame_start: usize,
        section_count: usize,
        mode_b: bool,
        limit_bytes: usize,
    ) -> usize {
        let section_dir_end = 24 + (section_count << 1);
        if mode_b {
            let header_words = (section_dir_end + 7) >> 3;
            let comp_start = frame_start + section_dir_end + header_words;
            if comp_start + 9 <= limit_bytes && read_u16_be(buf, comp_start) == LCA_DICT_FRAME_MAGIC
            {
                let comp_body_len = ((buf[comp_start + 3] as usize) << 16)
                    | ((buf[comp_start + 4] as usize) << 8)
                    | (buf[comp_start + 5] as usize);
                if comp_body_len >= 9 && comp_start + comp_body_len <= limit_bytes {
                    return section_dir_end + header_words + comp_body_len;
                }
            }
            return (section_dir_end + header_words).min(limit_bytes.saturating_sub(frame_start));
        }

        let chunk_flags = buf[frame_start + 21];
        let has_residual_nbt = (chunk_flags & 1) != 0;
        let scale_4b = (chunk_flags & 4) != 0;
        let unit_shift = if scale_4b { 2 } else { 0 };
        let residual_rel = read_u16_be(buf, frame_start + 22) as usize;
        let residual_start = frame_start + section_dir_end + (residual_rel << unit_shift);
        if !has_residual_nbt || residual_start + 2 > limit_bytes {
            return section_dir_end + (residual_rel << unit_shift);
        }

        let mut pos = residual_start;
        let bp_flags = read_u16_be(buf, pos);
        pos += 2;
        if (bp_flags & 8192) != 0 {
            if let Ok((mbnl_len, vm)) = read_varint(buf, pos, limit_bytes) {
                pos += vm + mbnl_len;
            }
        }
        if (bp_flags & 32) != 0 {
            pos += 1;
        }
        if (bp_flags & 64) != 0 && pos < limit_bytes {
            if let Ok((_, vs)) = read_varint(buf, pos, limit_bytes) {
                pos += vs;
            }
        }
        if (bp_flags & 128) != 0 {
            pos += 1;
        }
        if (bp_flags & 256) != 0 {
            pos += 1;
        }
        if (bp_flags & 512) != 0 && pos < limit_bytes {
            let sec_hm_count = buf[pos] as usize;
            pos += 1;
            for _ in 0..sec_hm_count {
                if pos >= limit_bytes {
                    break;
                }
                let code = buf[pos] as usize;
                pos += 1;
                if code >= 6 {
                    if let Ok((k_len, vk)) = read_varint(buf, pos, limit_bytes) {
                        pos += vk + k_len;
                    }
                }
                if let Ok((pay_len, vp)) = read_varint(buf, pos, limit_bytes) {
                    pos += vp + pay_len;
                }
            }
        }
        if (bp_flags & layer3::BP_FLAG_HAS_CHUNK_PATCH_TAIL) != 0 && pos < limit_bytes {
            if let Ok((patch_len, vp)) = read_varint(buf, pos, limit_bytes) {
                pos += vp + patch_len;
            }
        }
        if (bp_flags & 1024) != 0 && pos < limit_bytes {
            let mut s = (pos - frame_start + 255) >> SLAB_SHIFT;
            loop {
                let cand_end = frame_start + (s << SLAB_SHIFT);
                if cand_end >= limit_bytes {
                    let mut trimmed = limit_bytes;
                    while trimmed > pos && buf[trimmed - 1] == 0 {
                        trimmed -= 1;
                    }
                    pos = trimmed;
                    break;
                }
                let maybe_canary = read_u64_be(buf, cand_end);
                let maybe_dv = read_u32_be(buf, cand_end);
                if maybe_canary == LCA_FOOTER_CANARY_MAGIC
                    || (maybe_dv & CHUNK_FRAME_SELF_TAG_MASK) == CHUNK_FRAME_SELF_TAG_MAGIC
                {
                    let mut trimmed = cand_end;
                    while trimmed > pos && buf[trimmed - 1] == 0 {
                        trimmed -= 1;
                    }
                    pos = trimmed;
                    break;
                }
                s += 1;
            }
        }
        if pos <= limit_bytes {
            pos - frame_start
        } else {
            section_dir_end + (residual_rel << unit_shift)
        }
    }

    /// Executes 4-tier `SEC-DED` self-healing across `region_bytes` in-place (`leafanvil-ADR-004` Section 2.3).
    pub fn heal_region_bit_rot(region_bytes: &mut [u8]) -> LcaResult<RecoveryReport> {
        let sb = parse_and_validate_superblock(region_bytes)?;
        let dict = ChainedRegionDictionary::parse(region_bytes, &sb)?;
        let mut report = RecoveryReport {
            dict_secded_healed_words: dict.healed_words,
            ..Default::default()
        };
        if dict.healed_words > 0 {
            report.diagnostics.push(format!(
                "Tier-2 Dictionary SEC-DED healed {} word(s)",
                dict.healed_words
            ));
        }

        let has_secded = (sb.flags & 16) != 0;
        let header_len = 24 + (sb.section_count << 1);
        let header_words = (header_len + 7) >> 3;
        let limit_bytes = region_bytes.len() - FOOTER_SIZE;
        let mut needs_slab_scan = [false; 1024];
        let mut any_needs_scan = false;

        for chunk_idx in 0..1024 {
            let ptr_pos = POINTER_DIRECTORY_OFFSET + (chunk_idx << 3);
            let mut ptr = read_u64_be(region_bytes, ptr_pos);
            let syn8 = if has_secded {
                region_bytes[sb.secded_table_offset + chunk_idx]
            } else {
                0
            };

            if has_secded && (ptr != 0 || syn8 != 0) {
                match heal_single_bit_flip64(ptr, syn8) {
                    Some((healed, data_healed)) => {
                        if data_healed && healed != ptr {
                            ptr = healed;
                            write_u64_be(region_bytes, ptr_pos, ptr);
                            region_bytes[sb.secded_table_offset + chunk_idx] =
                                compute_hamming_secded8(ptr);
                            report.secded_healed_pointers += 1;
                            report.diagnostics.push(format!(
                                "Tier-1 Pointer SEC-DED healed 1-bit flip for chunkIdx={}",
                                chunk_idx
                            ));
                        } else if compute_hamming_secded8(ptr) != syn8 {
                            region_bytes[sb.secded_table_offset + chunk_idx] =
                                compute_hamming_secded8(ptr);
                            report.secded_healed_pointers += 1;
                        }
                    }
                    None => {
                        needs_slab_scan[chunk_idx] = true;
                        any_needs_scan = true;
                        continue;
                    }
                }
            }

            if ptr != 0 {
                let p = ChunkPointer64::unpack(ptr);
                let slab_off = p.byte_offset();
                if slab_off < sb.slab_pool_base_offset || slab_off + p.exact_byte_len20 > limit_bytes
                {
                    needs_slab_scan[chunk_idx] = true;
                    any_needs_scan = true;
                } else {
                    if p.codec1 == 1 && slab_off + header_len + header_words <= limit_bytes {
                        if let Ok(hdr_healed) = verify_and_heal_chunk_header_secded(
                            region_bytes,
                            slab_off,
                            header_len,
                            slab_off + header_len,
                        ) {
                            if hdr_healed > 0 {
                                report.chunk_header_secded_healed_words += hdr_healed;
                                report.diagnostics.push(format!(
                                    "Tier-3 Mode B ChunkFrame header SEC-DED healed {} word(s) in chunkIdx={}",
                                    hdr_healed, chunk_idx
                                ));
                            }
                        }
                    }
                    let dv_word = read_u32_be(region_bytes, slab_off);
                    if (dv_word & CHUNK_FRAME_SELF_TAG_MASK) != CHUNK_FRAME_SELF_TAG_MAGIC
                        || (((dv_word >> 16) & 0x03FF) as usize) != chunk_idx
                    {
                        needs_slab_scan[chunk_idx] = true;
                        any_needs_scan = true;
                    }
                }
            }
        }

        if any_needs_scan {
            let start_slab = sb.slab_pool_base_offset >> SLAB_SHIFT;
            let end_slab = limit_bytes >> SLAB_SHIFT;
            for slab in (start_slab..end_slab).rev() {
                let byte_pos = slab << SLAB_SHIFT;
                if byte_pos + header_len > limit_bytes {
                    continue;
                }
                let avail = limit_bytes - byte_pos;
                let slab_mode_b =
                    probe_looks_like_mode_b(region_bytes, byte_pos, header_len, header_words, avail);
                let dv_word = read_u32_be(region_bytes, byte_pos);
                if (dv_word & CHUNK_FRAME_SELF_TAG_MASK) != CHUNK_FRAME_SELF_TAG_MAGIC {
                    continue;
                }
                let slab_chunk_idx = ((dv_word >> 16) & 0x03FF) as usize;
                if !needs_slab_scan[slab_chunk_idx] {
                    continue;
                }
                let exact_len = estimate_self_describing_chunk_len(
                    region_bytes,
                    byte_pos,
                    sb.section_count,
                    slab_mode_b,
                    limit_bytes,
                );
                if exact_len < header_len || byte_pos + exact_len > limit_bytes {
                    continue;
                }
                let reconstructed =
                    ChunkPointer64::pack(slab, slab_mode_b, exact_len, 1);
                let ptr_pos = POINTER_DIRECTORY_OFFSET + (slab_chunk_idx << 3);
                write_u64_be(region_bytes, ptr_pos, reconstructed);
                if has_secded {
                    region_bytes[sb.secded_table_offset + slab_chunk_idx] =
                        compute_hamming_secded8(reconstructed);
                }
                needs_slab_scan[slab_chunk_idx] = false;
                report.slab_scan_reconstructed_pointers += 1;
                report.diagnostics.push(format!(
                    "Tier-4 Slab Reverse-Scan reconstructed pointer for chunkIdx={} at slab={} len={}",
                    slab_chunk_idx, slab, exact_len
                ));
            }
        }

        Ok(report)
    }
}

/// Direct `PumpkinMC` `crates/pumpkin-world` `SectionFrame` Bridge (`leafanvil-ADR-004` Section 1.2).
/// Decodes `.lca` `SectionFrame` payloads directly into `PumpkinMC` sub-chunk palettes and voxel/biome/light
/// buffers without materializing intermediate Mojang `CompoundTag` / `LongArrayTag` object trees.
pub mod pumpkin_bridge {
    use super::cbit::heal_region_bit_rot;
    use super::layer1::{
        compute_hamming_secded8, compute_superblock_checksum64, parse_and_validate_superblock,
        ChunkPointer64, FreeSlabAllocator, Superblock,
    };
    use super::layer2::{
        read_header_word64_be, strip_block_properties, ChainedRegionDictionary,
    };
    use super::layer3::{
        compute_patch_parity8, decode_mode_b_to_full_frame, decode_slab_local_palette,
        find_first_section_rel_start, find_residual_rel_end, find_section_rel_end,
        read_slab_local_entry_list, SectionPatch, BP_FLAG_HAS_CHUNK_PATCH_TAIL,
        PATCH_FLAG_BLOCK_LIGHT, PATCH_FLAG_REPLACEMENT_OFFSET, PATCH_FLAG_SKY_LIGHT,
        PATCH_FLAG_VOXEL_DELTAS,
    };
    use super::layer4::{
        create_uniform_decoded_section, curve_tables, decode_gray_planes, decode_section_frame,
        CURVE_H3, CURVE_Y_H2, LIGHT_MODE_CONST, LIGHT_MODE_GRAY_PLANES, LIGHT_MODE_RAW,
    };
    use super::*;

    /// `pumpkin.toml` `[world.chunk]` configuration selector (`leafanvil-ADR-004` Section 1.2).
    #[derive(Debug, Clone, PartialEq, Eq)]
    pub enum PumpkinChunkFormatMode {
        ModeA0Lz,
        ModeBLcd2,
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct PumpkinWorldChunkConfig {
        pub chunk_type: String,
        pub mode: PumpkinChunkFormatMode,
    }

    impl PumpkinWorldChunkConfig {
        pub fn lca_default() -> Self {
            Self {
                chunk_type: "lca".to_string(),
                mode: PumpkinChunkFormatMode::ModeA0Lz,
            }
        }
    }

    /// Direct `PumpkinMC` block palette representation (`Uniform` $O(1)$ vs `Heterogeneous` 4,096-entry index array).
    #[derive(Debug, Clone, PartialEq, Eq)]
    pub enum PumpkinBlockPalette {
        Uniform(String),
        Heterogeneous {
            palette: Vec<String>,
            yzx_indices: Vec<u16>,
        },
    }

    impl PumpkinBlockPalette {
        #[inline]
        pub fn get_yzx(&self, yzx: usize) -> &str {
            match self {
                PumpkinBlockPalette::Uniform(s) => s.as_str(),
                PumpkinBlockPalette::Heterogeneous {
                    palette,
                    yzx_indices,
                } => {
                    let idx = yzx_indices.get(yzx).copied().unwrap_or(0) as usize;
                    palette.get(idx).map(|s| s.as_str()).unwrap_or("minecraft:air")
                }
            }
        }

        #[inline]
        pub fn block_at(&self, x: usize, y: usize, z: usize) -> &str {
            self.get_yzx(((y & 15) << 8) | ((z & 15) << 4) | (x & 15))
        }
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct PumpkinBiomePalette {
        pub palette: Vec<String>,
        pub cells_yzx: [usize; BIOME_CELLS],
    }

    impl PumpkinBiomePalette {
        #[inline]
        pub fn get_cell(&self, bx: usize, by: usize, bz: usize) -> &str {
            let yzx = ((by & 3) << 4) | ((bz & 3) << 2) | (bx & 3);
            let idx = self.cells_yzx[yzx];
            self.palette
                .get(idx)
                .or_else(|| self.palette.first())
                .map(|s| s.as_str())
                .unwrap_or("minecraft:plains")
        }
    }

    /// `PumpkinMC` `SubChunk` populated directly from a Layer 4 `SectionFrame`.
    #[derive(Debug, Clone)]
    pub struct PumpkinSubChunk {
        pub section_y: i32,
        pub inline_uniform: bool,
        pub octree_occupancy_mask64: i64,
        pub block_states: PumpkinBlockPalette,
        pub full_block_palette: Vec<String>,
        pub biomes: PumpkinBiomePalette,
        pub sky_light: Option<Vec<u8>>,
        pub block_light: Option<Vec<u8>>,
    }

    impl PumpkinSubChunk {
        #[inline]
        pub fn full_block_at(&self, x: usize, y: usize, z: usize) -> &str {
            let yzx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            match &self.block_states {
                PumpkinBlockPalette::Uniform(s) => self
                    .full_block_palette
                    .first()
                    .map(|v| v.as_str())
                    .unwrap_or(s.as_str()),
                PumpkinBlockPalette::Heterogeneous {
                    palette,
                    yzx_indices,
                } => {
                    let idx = yzx_indices.get(yzx).copied().unwrap_or(0) as usize;
                    self.full_block_palette
                        .get(idx)
                        .or_else(|| palette.get(idx))
                        .or_else(|| self.full_block_palette.first())
                        .or_else(|| palette.first())
                        .map(|s| s.as_str())
                        .unwrap_or("minecraft:air")
                }
            }
        }

        /// Computes the 64-bit FNV-1a voxel digest over $y \in 0..15, z \in 0..15, x \in 0..15$ (`leafanvil-ADR-004` Section 2.2).
        pub fn compute_voxel_fnv1a64(&self) -> u64 {
            let mut h = FNV1A_64_OFFSET_BASIS;
            for y in 0..16 {
                for z in 0..16 {
                    for x in 0..16 {
                        let state = self.block_states.block_at(x, y, z);
                        for &b in state.as_bytes() {
                            h = (h ^ (b as u64)).wrapping_mul(FNV1A_64_PRIME);
                        }
                        h = h.wrapping_mul(FNV1A_64_PRIME);
                    }
                }
            }
            h
        }

        /// Computes the 64-bit FNV-1a biome digest over $by \in 0..3, bz \in 0..3, bx \in 0..3$ (`leafanvil-ADR-004` Section 2.2).
        pub fn compute_biome_fnv1a64(&self) -> u64 {
            let mut h = FNV1A_64_OFFSET_BASIS;
            for by in 0..4 {
                for bz in 0..4 {
                    for bx in 0..4 {
                        let biome = self.biomes.get_cell(bx, by, bz);
                        for &b in biome.as_bytes() {
                            h = (h ^ (b as u64)).wrapping_mul(FNV1A_64_PRIME);
                        }
                        h = h.wrapping_mul(FNV1A_64_PRIME);
                    }
                }
            }
            h
        }

        pub fn compute_sky_light_fnv1a64(&self) -> u64 {
            compute_bytes_fnv1a64(self.sky_light.as_deref())
        }

        pub fn compute_block_light_fnv1a64(&self) -> u64 {
            compute_bytes_fnv1a64(self.block_light.as_deref())
        }
    }

    #[inline]
    pub fn compute_bytes_fnv1a64(bytes: Option<&[u8]>) -> u64 {
        let mut h = FNV1A_64_OFFSET_BASIS;
        if let Some(slice) = bytes {
            for &b in slice {
                h = (h ^ (b as u64)).wrapping_mul(FNV1A_64_PRIME);
            }
        }
        h
    }

    /// Direct `PumpkinMC` `ChunkData` hydrated without full-chunk NBT deserialization on the voxel path.
    #[derive(Debug, Clone)]
    pub struct PumpkinChunkData {
        pub chunk_x: i32,
        pub chunk_z: i32,
        pub min_section_y: i32,
        pub section_count: usize,
        pub data_version: i32,
        pub generation_token8: u8,
        pub raw_pointer_word64: u64,
        pub pointer_secded_syndrome8: u8,
        pub last_update: i64,
        pub inhabited_time: i64,
        pub status_ordinal: u8,
        pub sub_chunks: Vec<PumpkinSubChunk>,
        pub residual_nbt_slice: Vec<u8>,
    }

    pub struct LcaPumpkinBridge;

    /// Pre-encoded section payload produced on a parallel `Rayon` worker thread without locking
    /// the region's Layer 2 dictionary (`leafanvil-ADR-004`).
    #[derive(Debug, Clone)]
    pub struct PreEncodedSection {
        pub section_y: i32,
        pub is_uniform_block: bool,
        pub curve_id: usize,
        pub block_mode: u8,
        pub octree_mask64: u64,
        pub sorted_states: Vec<String>,
        pub block_payload: Vec<u8>,
        pub biome_palette: Vec<String>,
        pub biome_rle_payload: Vec<u8>,
        pub sky_mode: u8,
        pub blk_mode: u8,
        pub sky_const: Option<u8>,
        pub blk_const: Option<u8>,
        pub light_payload: Vec<u8>,
    }

    /// Pre-encoded chunk column ready for $O(1)$ Layer 2 dictionary binding, Mode B `LCD2`
    /// (`0x4C44` `"LD"`) framing, and positional `CoW` slab allocation.
    #[derive(Debug, Clone)]
    pub struct PreEncodedChunk {
        pub chunk_x: i32,
        pub chunk_z: i32,
        pub min_section_y: i32,
        pub section_count: usize,
        pub data_version: i32,
        pub last_update: i64,
        pub inhabited_time: i64,
        pub status_ordinal: u8,
        pub sections: Vec<PreEncodedSection>,
        pub unique_states: Vec<String>,
        pub unique_biomes: Vec<String>,
    }

    /// Byte-range receipt returned by `commit_pre_encoded_chunks_cow` for positional `pwrite` (`write_all_at`).
    #[derive(Debug, Clone)]
    pub struct LcaCommitReceipt {
        pub dict_dirty_ranges: Vec<(usize, usize)>,
        pub chunk_commits: Vec<(usize, usize, usize, ChunkPointer64)>,
        pub footer_canary_offset: usize,
    }

    impl LcaPumpkinBridge {
        /// Reads and validates a single chunk `(cx, cz)` from `region_bytes` directly into `PumpkinChunkData`.
        /// If `allow_self_heal` is true, executes 4-tier `SEC-DED` self-healing before failing closed on `CBIT T1..T8`.
        pub fn read_chunk_direct(
            region_bytes: &[u8],
            cx: i32,
            cz: i32,
            allow_self_heal: bool,
        ) -> LcaResult<Option<PumpkinChunkData>> {
            match Self::read_chunk_strict(region_bytes, cx, cz) {
                Ok(res) => Ok(res),
                Err(first_err) => {
                    if !allow_self_heal {
                        return Err(first_err);
                    }
                    let mut healed_copy = region_bytes.to_vec();
                    let report = heal_region_bit_rot(&mut healed_copy)?;
                    if report.total_healed() == 0 {
                        return Err(first_err);
                    }
                    Self::read_chunk_strict(&healed_copy, cx, cz)
                }
            }
        }

        pub fn read_chunk_strict(
            region_bytes: &[u8],
            cx: i32,
            cz: i32,
        ) -> LcaResult<Option<PumpkinChunkData>> {
            let sb = parse_and_validate_superblock(region_bytes)?;
            let dict = ChainedRegionDictionary::parse_strict(region_bytes, &sb)?;
            Self::read_chunk_with_dict(region_bytes, &sb, &dict, cx, cz, None)
        }

        pub fn read_chunk_with_dict(
            region_bytes: &[u8],
            sb: &Superblock,
            dict: &ChainedRegionDictionary,
            cx: i32,
            cz: i32,
            y_window: Option<(i32, i32)>,
        ) -> LcaResult<Option<PumpkinChunkData>> {
            let rx = (cx & 31) as usize;
            let rz = (cz & 31) as usize;
            let chunk_idx = rx | (rz << 5);
            let ptr_pos = POINTER_DIRECTORY_OFFSET + (chunk_idx << 3);
            let raw_ptr = read_u64_be(region_bytes, ptr_pos);
            let syn8 = region_bytes[sb.secded_table_offset + chunk_idx];
            if raw_ptr == 0 && syn8 == 0 {
                return Ok(None);
            }
            if (sb.flags & 16) != 0 && compute_hamming_secded8(raw_ptr) != syn8 {
                return Err(LcaError::CorruptRegionEntry(format!(
                    "CBIT T3: Pointer SEC-DED syndrome mismatch at chunkIdx={}",
                    chunk_idx
                )));
            }
            let ptr = ChunkPointer64::unpack(raw_ptr);
            if !ptr.is_present() {
                return Ok(None);
            }
            let slab_off = ptr.byte_offset();
            let limit_bytes = region_bytes.len() - FOOTER_SIZE;
            if slab_off < sb.slab_pool_base_offset || slab_off + ptr.exact_byte_len20 > limit_bytes
            {
                return Err(LcaError::CorruptRegionEntry(format!(
                    "CBIT T3/T4: Chunk slab range [{}..{}] out of bounds {}",
                    slab_off,
                    slab_off + ptr.exact_byte_len20,
                    limit_bytes
                )));
            }

            let header_len = 24 + (sb.section_count << 1);
            if ptr.exact_byte_len20 < header_len {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T4: ChunkFrame exactByteLen20 smaller than headerLen".to_string(),
                ));
            }

            let frame_vec: Vec<u8>;
            let frame: &[u8] = if ptr.codec1 == 1 {
                let header_words = (header_len + 7) >> 3;
                if ptr.exact_byte_len20 < header_len + header_words {
                    return Err(LcaError::CorruptRegionEntry(
                        "CBIT T3c: Truncated Mode B header SEC-DED syndromes".to_string(),
                    ));
                }
                for w in 0..header_words {
                    let word64 = read_header_word64_be(region_bytes, slab_off, header_len, w);
                    let h_syn8 = region_bytes[slab_off + header_len + w];
                    if compute_hamming_secded8(word64) != h_syn8 {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T3c: Mode B header required SEC-DED healing in strict mode"
                                .to_string(),
                        ));
                    }
                }
                let comp_start = slab_off + header_len + header_words;
                let comp_len = ptr.exact_byte_len20 - (header_len + header_words);
                frame_vec = decode_mode_b_to_full_frame(
                    region_bytes,
                    slab_off,
                    header_len,
                    comp_start,
                    comp_len,
                    dict,
                )?;
                &frame_vec
            } else {
                &region_bytes[slab_off..slab_off + ptr.exact_byte_len20]
            };

            let raw_dv_word = read_u32_be(frame, 0);
            if (raw_dv_word & CHUNK_FRAME_SELF_TAG_MASK) != CHUNK_FRAME_SELF_TAG_MAGIC {
                return Err(LcaError::CorruptRegionEntry(format!(
                    "CBIT T3b: Invalid selfTagAndDataVersion canary 0x{:08x}",
                    raw_dv_word
                )));
            }
            let tag_chunk_idx = ((raw_dv_word >> 16) & 0x03FF) as usize;
            if tag_chunk_idx != chunk_idx {
                return Err(LcaError::CorruptRegionEntry(format!(
                    "CBIT T3b: ChunkFrame chunkIdx mismatch (tag={} != dir={})",
                    tag_chunk_idx, chunk_idx
                )));
            }
            let mut chunk_data_version = (raw_dv_word & 0xFFFF) as i32;
            if chunk_data_version == 0 {
                chunk_data_version = sb.default_data_version;
            }
            let last_update = read_u64_be(frame, 4) as i64;
            let inhabited_time = read_u64_be(frame, 12) as i64;
            let status_ordinal = frame[20];
            let chunk_flags = frame[21];
            let has_residual_nbt = (chunk_flags & 1) != 0;
            let has_slab_local_palette = (chunk_flags & 2) != 0;
            let scale_4b = (chunk_flags & 4) != 0;
            let default_biome_code = ((chunk_flags >> 3) & 0x1F) as isize;
            let default_biome_dict_id = if default_biome_code > 0 {
                default_biome_code - 1
            } else {
                -1
            };
            let unit_shift = if scale_4b { 2 } else { 0 };
            let section_dir_end = header_len;
            let residual_rel_offset = read_u16_be(frame, 22) as usize;
            let residual_rel_start = if has_residual_nbt {
                section_dir_end + (residual_rel_offset << unit_shift)
            } else {
                frame.len()
            };
            if residual_rel_start > frame.len() {
                return Err(LcaError::CorruptRegionEntry(
                    "CBIT T5: residualRelStart exceeds ChunkFrame length".to_string(),
                ));
            }

            let mut section_entries = vec![0u16; sb.section_count];
            let mut section_rel_starts = vec![-1isize; sb.section_count];
            for i in 0..sb.section_count {
                let entry16 = read_u16_be(frame, 24 + (i << 1));
                section_entries[i] = entry16;
                if entry16 != 0 && (entry16 & 0x8000) == 0 {
                    let rel_pos = section_dir_end + (((entry16 as usize) - 1) << unit_shift);
                    if rel_pos >= frame.len() {
                        return Err(LcaError::CorruptRegionEntry(format!(
                            "CBIT T5: Section {} offset {} exceeds frame length {}",
                            i,
                            rel_pos,
                            frame.len()
                        )));
                    }
                    for prev in 0..i {
                        if section_rel_starts[prev] == rel_pos as isize {
                            return Err(LcaError::CorruptRegionEntry(
                                "CBIT T5: Duplicate non-uniform sectionOffset".to_string(),
                            ));
                        }
                    }
                    section_rel_starts[i] = rel_pos as isize;
                }
            }

            let first_sec_rel_start =
                find_first_section_rel_start(&section_rel_starts, residual_rel_start);
            let mut local_pal = if has_slab_local_palette {
                Some(decode_slab_local_palette(
                    frame,
                    section_dir_end,
                    first_sec_rel_start,
                    &dict.canonical_states,
                )?)
            } else {
                None
            };

            let residual_rel_end =
                find_residual_rel_end(&section_rel_starts, residual_rel_start, frame.len());

            // Pre-section ChunkPatchTail resolution (LCA-ICD-1.0 Section 6.4 & 8.3)
            let mut section_patches: Vec<Option<SectionPatch>> = vec![None; sb.section_count];
            if has_residual_nbt && residual_rel_start + 2 <= residual_rel_end {
                let bp_flags = read_u16_be(frame, residual_rel_start);
                if (bp_flags & BP_FLAG_HAS_CHUNK_PATCH_TAIL) != 0 {
                    let mut pos = residual_rel_start + 2;
                    let limit = residual_rel_end;
                    if (bp_flags & 8192) != 0 {
                        let (mbnl_len, vm) = read_varint(frame, pos, limit)?;
                        pos += vm + mbnl_len;
                    }
                    if (bp_flags & 32) != 0 {
                        pos += 1;
                    }
                    if (bp_flags & 64) != 0 {
                        let (_, vs) = read_varint(frame, pos, limit)?;
                        pos += vs;
                    }
                    if (bp_flags & 128) != 0 {
                        pos += 1;
                    }
                    if (bp_flags & 256) != 0 {
                        pos += 1;
                    }
                    if (bp_flags & 512) != 0 {
                        let sec_hm_count = frame[pos] as usize;
                        pos += 1;
                        for _ in 0..sec_hm_count {
                            let code = frame[pos] as usize;
                            pos += 1;
                            if code >= 6 {
                                let (k_len, vk) = read_varint(frame, pos, limit)?;
                                pos += vk + k_len;
                            }
                            let (pay_len, vp) = read_varint(frame, pos, limit)?;
                            pos += vp + pay_len;
                        }
                    }
                    let (patch_tail_len, vpt) = read_varint(frame, pos, limit)?;
                    pos += vpt;
                    if patch_tail_len < 3 || pos + patch_tail_len > limit {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T5: Truncated chunkPatchTail payload".to_string(),
                        ));
                    }
                    let payload_end = pos + patch_tail_len - 1;
                    let stored_parity8 = frame[payload_end];
                    let expected_parity8 = compute_patch_parity8(frame, pos, patch_tail_len - 1);
                    if stored_parity8 != expected_parity8 {
                        return Err(LcaError::CorruptRegionEntry(
                            "CBIT T6b: ChunkPatchTail parity syndrome mismatch".to_string(),
                        ));
                    }
                    let mut p_pos = pos;
                    let (appended_state_count, vas) = read_varint(frame, p_pos, payload_end)?;
                    p_pos += vas;
                    if appended_state_count > 0 {
                        let lp = local_pal.as_mut().ok_or_else(|| {
                            LcaError::CorruptRegionEntry(
                                "CBIT T5: Appended micro-palette requires hasSlabLocalPalette"
                                    .to_string(),
                            )
                        })?;
                        let base_count = lp.base_block_names.len();
                        p_pos = read_slab_local_entry_list(
                            frame,
                            p_pos,
                            payload_end,
                            appended_state_count,
                            &layer2::STATIC_CORE_STATES,
                            true,
                            &mut lp.base_block_names,
                        )?;
                        for i in 0..appended_state_count {
                            lp.canonical_states
                                .push(lp.base_block_names[base_count + i].clone());
                        }
                        let (appended_prop_count, vap) = read_varint(frame, p_pos, payload_end)?;
                        p_pos += vap;
                        for _ in 0..appended_prop_count {
                            let (state_idx, vsi) = read_varint(frame, p_pos, payload_end)?;
                            p_pos += vsi;
                            let prop_tag = frame[p_pos] as usize;
                            p_pos += 1;
                            if (prop_tag & 0x80) != 0 {
                                let prop_id = prop_tag & 0x7F;
                                lp.canonical_states[state_idx] = format!(
                                    "{}{}",
                                    lp.base_block_names[state_idx],
                                    layer2::STATIC_CORE_PROP_SUFFIXES[prop_id]
                                );
                            } else {
                                let (global_id, vgi) = read_varint(frame, p_pos, payload_end)?;
                                p_pos += vgi;
                                lp.canonical_states[state_idx] =
                                    dict.canonical_states[global_id].clone();
                            }
                        }
                    }

                    let patched_sec_count = frame[p_pos] as usize;
                    p_pos += 1;
                    let tables = curve_tables();
                    for _ in 0..patched_sec_count {
                        let sec_idx = frame[p_pos] as usize;
                        let patch_flags = frame[p_pos + 1];
                        p_pos += 2;
                        let mut repl_entry16 = 0u16;
                        if (patch_flags & PATCH_FLAG_REPLACEMENT_OFFSET) != 0 {
                            repl_entry16 = read_u16_be(frame, p_pos);
                            p_pos += 2;
                            section_entries[sec_idx] = repl_entry16;
                            if repl_entry16 != 0 && (repl_entry16 & 0x8000) == 0 {
                                let rel_pos =
                                    section_dir_end + (((repl_entry16 as usize) - 1) << unit_shift);
                                section_rel_starts[sec_idx] = rel_pos as isize;
                            } else {
                                section_rel_starts[sec_idx] = -1;
                            }
                        }
                        let mut yzx_coords = Vec::new();
                        let mut dict_ids = Vec::new();
                        if (patch_flags & PATCH_FLAG_VOXEL_DELTAS) != 0 {
                            let (delta_count, vdc) = read_varint(frame, p_pos, payload_end)?;
                            p_pos += vdc;
                            for _ in 0..delta_count {
                                let yzx12 = read_u16_be(frame, p_pos);
                                p_pos += 2;
                                let (did, vdi) = read_varint(frame, p_pos, payload_end)?;
                                p_pos += vdi;
                                yzx_coords.push(yzx12);
                                dict_ids.push(did);
                            }
                        }
                        let mut sky_patch = None;
                        if (patch_flags & PATCH_FLAG_SKY_LIGHT) != 0 {
                            let mode = frame[p_pos];
                            p_pos += 1;
                            if mode == LIGHT_MODE_CONST {
                                let c = frame[p_pos] & 0x0F;
                                p_pos += 1;
                                sky_patch = Some(vec![(c << 4) | c; 2048]);
                            } else if mode == LIGHT_MODE_GRAY_PLANES {
                                let (np, sp) = decode_gray_planes(
                                    frame,
                                    p_pos,
                                    payload_end,
                                    &tables.curve_to_yzx[CURVE_Y_H2],
                                )?;
                                p_pos = np;
                                sky_patch = Some(sp);
                            } else if mode == LIGHT_MODE_RAW {
                                sky_patch = Some(frame[p_pos..p_pos + 2048].to_vec());
                                p_pos += 2048;
                            }
                        }
                        let mut blk_patch = None;
                        if (patch_flags & PATCH_FLAG_BLOCK_LIGHT) != 0 {
                            let mode = frame[p_pos];
                            p_pos += 1;
                            if mode == LIGHT_MODE_CONST {
                                let c = frame[p_pos] & 0x0F;
                                p_pos += 1;
                                blk_patch = Some(vec![(c << 4) | c; 2048]);
                            } else if mode == LIGHT_MODE_GRAY_PLANES {
                                let (np, bp) = decode_gray_planes(
                                    frame,
                                    p_pos,
                                    payload_end,
                                    &tables.curve_to_yzx[CURVE_H3],
                                )?;
                                p_pos = np;
                                blk_patch = Some(bp);
                            } else if mode == LIGHT_MODE_RAW {
                                blk_patch = Some(frame[p_pos..p_pos + 2048].to_vec());
                                p_pos += 2048;
                            }
                        }
                        section_patches[sec_idx] = Some(SectionPatch {
                            section_index: sec_idx,
                            patch_flags,
                            replacement_section_entry16: repl_entry16,
                            yzx_coords,
                            dict_ids,
                            sky_light_2048: sky_patch,
                            block_light_2048: blk_patch,
                        });
                    }
                }
            }

            let active_canon_states: &[String] = match local_pal {
                Some(ref lp) => &lp.canonical_states,
                None => &dict.canonical_states,
            };
            let active_biomes: &[String] = match local_pal {
                Some(ref lp) => &lp.biomes,
                None => &dict.biomes,
            };
            let active_templates: &[layer3::BiomeTemplate] = match local_pal {
                Some(ref lp) => &lp.biome_templates,
                None => &[],
            };

            let mut sub_chunks = Vec::new();
            for i in 0..sb.section_count {
                let entry16 = section_entries[i];
                let sec_patch = &section_patches[i];
                if entry16 == 0 && sec_patch.is_none() {
                    continue;
                }
                let section_y = sb.min_section_y + (i as i32);
                if let Some((min_sy, max_sy)) = y_window {
                    if section_y < min_sy || section_y > max_sy {
                        continue;
                    }
                }
                let mut dec = if entry16 == 0 || (entry16 & 0x8000) != 0 {
                    create_uniform_decoded_section(
                        section_y,
                        entry16,
                        has_slab_local_palette,
                        active_canon_states,
                        active_biomes,
                        active_templates,
                        default_biome_dict_id,
                    )?
                } else {
                    let rel_start = section_rel_starts[i] as usize;
                    let rel_end = find_section_rel_end(
                        &section_rel_starts,
                        i,
                        residual_rel_start,
                        frame.len(),
                    );
                    decode_section_frame(
                        frame,
                        rel_start,
                        rel_end,
                        section_y,
                        active_canon_states,
                        active_biomes,
                        active_templates,
                        default_biome_dict_id,
                    )?
                };

                if let Some(ref patch) = sec_patch {
                    if (patch.patch_flags & PATCH_FLAG_VOXEL_DELTAS) != 0
                        && !patch.yzx_coords.is_empty()
                    {
                        if dec.yzx_palette_indices.is_empty() {
                            dec.yzx_palette_indices = vec![0u16; SECTION_VOXELS];
                        }
                        for (k, &yzx12) in patch.yzx_coords.iter().enumerate() {
                            let did = patch.dict_ids[k];
                            let state_str = &active_canon_states[did];
                            let p_idx = match dec
                                .block_palette
                                .iter()
                                .position(|s| s == state_str)
                            {
                                Some(idx) => idx as u16,
                                None => {
                                    dec.block_palette.push(state_str.clone());
                                    (dec.block_palette.len() - 1) as u16
                                }
                            };
                            dec.yzx_palette_indices[yzx12 as usize] = p_idx;
                        }
                        dec.inline_uniform = false;
                    }
                    if let Some(ref sky) = patch.sky_light_2048 {
                        dec.sky_light_2048 = Some(sky.clone());
                    }
                    if let Some(ref blk) = patch.block_light_2048 {
                        dec.block_light_2048 = Some(blk.clone());
                    }
                }

                //Golden manifest stores stripped base block names in AnvilChunkView#blockIdAt(x,y,z)
                let base_palette: Vec<String> = dec
                    .block_palette
                    .iter()
                    .map(|s| strip_block_properties(s).to_string())
                    .collect();
                let block_states = if base_palette.len() == 1 && dec.yzx_palette_indices.is_empty() {
                    PumpkinBlockPalette::Uniform(base_palette[0].clone())
                } else {
                    PumpkinBlockPalette::Heterogeneous {
                        palette: base_palette,
                        yzx_indices: if dec.yzx_palette_indices.is_empty() {
                            vec![0u16; SECTION_VOXELS]
                        } else {
                            dec.yzx_palette_indices
                        },
                    }
                };

                sub_chunks.push(PumpkinSubChunk {
                    section_y: dec.section_y,
                    inline_uniform: dec.inline_uniform,
                    octree_occupancy_mask64: dec.octree_occupancy_mask64,
                    block_states,
                    full_block_palette: dec.block_palette,
                    biomes: PumpkinBiomePalette {
                        palette: dec.biome_palette,
                        cells_yzx: dec.biome_cells_yzx,
                    },
                    sky_light: dec.sky_light_2048,
                    block_light: dec.block_light_2048,
                });
            }

            let residual_nbt_slice = if has_residual_nbt && residual_rel_start < residual_rel_end {
                frame[residual_rel_start..residual_rel_end].to_vec()
            } else {
                Vec::new()
            };

            Ok(Some(PumpkinChunkData {
                chunk_x: (sb.region_x << 5) | (rx as i32),
                chunk_z: (sb.region_z << 5) | (rz as i32),
                min_section_y: sb.min_section_y,
                section_count: sb.section_count,
                data_version: chunk_data_version,
                generation_token8: ptr.generation8,
                raw_pointer_word64: raw_ptr,
                pointer_secded_syndrome8: syn8,
                last_update,
                inhabited_time,
                status_ordinal,
                sub_chunks,
                residual_nbt_slice,
            }))
        }

        /// Creates a valid empty `.lca` (`LCA-ICD-1.0`) region archive pre-seeded with `"minecraft:air"`
        /// (state ID `0`) and `"minecraft:plains"` (biome ID `0`).
        pub fn create_empty_region(
            region_x: i32,
            region_z: i32,
            min_section_y: i32,
            section_count: usize,
            default_data_version: i32,
        ) -> Vec<u8> {
            let dict_root_offset = DICT_ROOT_OFFSET;
            let dict_root_capacity = 16384usize;
            let secded_table_offset = (dict_root_offset + dict_root_capacity + 255) & !255;
            let slab_pool_base_offset = (secded_table_offset + SECDED_TABLE_SIZE + 255) & !255;
            let total_len = slab_pool_base_offset + FOOTER_SIZE;
            let mut buf = vec![0u8; total_len];

            write_u64_be(&mut buf, 0x00, LCA_SUPERBLOCK_MAGIC);
            buf[0x08..0x0A].copy_from_slice(&LCA_FORMAT_VERSION.to_be_bytes());
            buf[0x0A] = 0; // storageKind = region
            buf[0x0B] = 16; // flags: bit 4 = pointer SecDed active
            buf[0x0C..0x10].copy_from_slice(&region_x.to_be_bytes());
            buf[0x10..0x14].copy_from_slice(&region_z.to_be_bytes());
            buf[0x14..0x18].copy_from_slice(&min_section_y.to_be_bytes());
            buf[0x18..0x1A].copy_from_slice(&(section_count as u16).to_be_bytes());
            buf[0x1A..0x1C].copy_from_slice(&(SLAB_SHIFT as u16).to_be_bytes());
            buf[0x1C..0x20].copy_from_slice(&default_data_version.to_be_bytes());
            let dict_off_and_len =
                ((dict_root_capacity as u64) << 32) | (dict_root_offset as u64);
            write_u64_be(&mut buf, 0x20, dict_off_and_len);
            let lod_and_secded =
                ((SECDED_TABLE_SIZE as u64) << 32) | (secded_table_offset as u64);
            write_u64_be(&mut buf, 0x28, lod_and_secded);
            write_u64_be(&mut buf, 0x30, slab_pool_base_offset as u64);
            let checksum = compute_superblock_checksum64(&buf, 0);
            write_u64_be(&mut buf, 0x38, checksum);

            // Seed Root Dictionary with "minecraft:air" (state 0) and "minecraft:plains" (biome 0)
            Self::write_dict_segment(
                &mut buf[dict_root_offset..dict_root_offset + dict_root_capacity],
                &["minecraft:air".to_string()],
                &["minecraft:plains".to_string()],
                2u64,
            );
            write_u64_be(&mut buf, slab_pool_base_offset, LCA_FOOTER_CANARY_MAGIC);
            buf
        }

        #[inline]
        fn varint_len(mut val: usize) -> usize {
            let mut len = 1usize;
            while (val & !0x7F) != 0 {
                len += 1;
                val >>= 7;
            }
            len
        }

        fn write_varint_vec(dst: &mut Vec<u8>, mut val: usize) {
            while (val & !0x7F) != 0 {
                dst.push(((val & 0x7F) | 0x80) as u8);
                val >>= 7;
            }
            dst.push((val & 0x7F) as u8);
        }

        #[inline]
        fn bounded_gap240_len(val: usize) -> usize {
            if val < 240 { 1 } else { 2 }
        }

        #[inline]
        fn write_bounded_gap240(dst: &mut Vec<u8>, val: usize) {
            if val < 240 {
                dst.push(val as u8);
            } else {
                dst.push((0xF0 | ((val >> 8) & 0x0F)) as u8);
                dst.push((val & 0xFF) as u8);
            }
        }

        fn select_optimal_curve_1pass(
            yzx_states: &[usize; SECTION_VOXELS],
            dominant_idx: usize,
        ) -> usize {
            let mut same_x = 0usize;
            let mut same_y = 0usize;
            let mut same_z = 0usize;
            let mut y = 0usize;
            while y < 16 {
                let mut z = 0usize;
                while z < 16 {
                    let mut x = 0usize;
                    while x < 16 {
                        let idx = (y << 8) | (z << 4) | x;
                        let cur = yzx_states[idx];
                        if cur != dominant_idx {
                            if cur == yzx_states[idx + 1] {
                                same_x += 1;
                            }
                            if cur == yzx_states[idx + 16] {
                                same_z += 1;
                            }
                            if cur == yzx_states[idx + 256] {
                                same_y += 1;
                            }
                        }
                        x += 2;
                    }
                    z += 2;
                }
                y += 2;
            }
            if same_y > same_x && same_y > same_z {
                return layer4::CURVE_XZY;
            }
            if same_x + same_z >= same_y {
                return if same_x > same_z + 16 {
                    layer4::CURVE_YZX
                } else {
                    layer4::CURVE_Y_H2
                };
            }
            layer4::CURVE_H3
        }

        fn populate_compacted_curve_palette_idx(
            mapped_yzx: &[usize; SECTION_VOXELS],
            curve_id: usize,
            octree_mask64: u64,
            curve_palette_idx: &mut [usize; SECTION_VOXELS],
        ) -> usize {
            let curve_to_yzx = &layer4::curve_tables().curve_to_yzx[curve_id];
            if octree_mask64 != u64::MAX && octree_mask64 != 0 {
                if curve_id == layer4::CURVE_H3 {
                    let mut m = octree_mask64;
                    let mut out_pos = 0usize;
                    while m != 0 {
                        let base_h3 = (m.trailing_zeros() as usize) << 6;
                        m &= m - 1;
                        for k in 0..64 {
                            curve_palette_idx[out_pos] = mapped_yzx[curve_to_yzx[base_h3 + k]];
                            out_pos += 1;
                        }
                    }
                    return out_pos;
                }
                if curve_id == layer4::CURVE_YZX || curve_id == layer4::CURVE_Y_H2 {
                    let mut out_pos = 0usize;
                    for oct_y in 0..4 {
                        if ((octree_mask64 >> (oct_y << 4)) & 0xFFFF) != 0 {
                            let base_h = oct_y << 10;
                            if curve_id == layer4::CURVE_YZX {
                                curve_palette_idx[out_pos..out_pos + 1024]
                                    .copy_from_slice(&mapped_yzx[base_h..base_h + 1024]);
                                out_pos += 1024;
                            } else {
                                for k in 0..1024 {
                                    curve_palette_idx[out_pos] =
                                        mapped_yzx[curve_to_yzx[base_h + k]];
                                    out_pos += 1;
                                }
                            }
                        }
                    }
                    return out_pos;
                }
            }
            if curve_id == layer4::CURVE_YZX {
                curve_palette_idx.copy_from_slice(mapped_yzx);
            } else {
                for h in 0..SECTION_VOXELS {
                    curve_palette_idx[h] = mapped_yzx[curve_to_yzx[h]];
                }
            }
            SECTION_VOXELS
        }

        fn encode_minority_containers_single_pass(
            curve_palette_idx: &[usize],
            active_voxels: usize,
            palette_size: usize,
            dst: &mut Vec<u8>,
        ) {
            let mut last_cursor = vec![0usize; palette_size];
            let mut run_counts = vec![0usize; palette_size];
            let mut total_voxels = vec![0usize; palette_size];
            let mut single_pos = vec![0usize; palette_size];
            let mut runs_per_state: Vec<Vec<(usize, usize)>> =
                vec![Vec::new(); palette_size];

            let mut i = 0usize;
            while i < active_voxels {
                let p = curve_palette_idx[i];
                if p == 0 || p >= palette_size {
                    i += 1;
                    continue;
                }
                let start = i;
                i += 1;
                while i < active_voxels && curve_palette_idx[i] == p {
                    i += 1;
                }
                let adj_gap = if run_counts[p] == 0 {
                    start
                } else {
                    start - last_cursor[p] - 1
                };
                let len = i - start;
                last_cursor[p] = i;
                total_voxels[p] += len;
                single_pos[p] = start;
                run_counts[p] += 1;
                runs_per_state[p].push((adj_gap, len - 1));
            }

            let mut bitmap_words = [0u64; 64];
            for p in 1..palette_size {
                let r_count = run_counts[p];
                let t_vox = total_voxels[p];
                let s_pos = single_pos[p];
                let runs = &runs_per_state[p];
                if r_count == 0 {
                    let word16 = ((layer4::TAG_DELTA_ARRAY << 14) | 0) as u16;
                    dst.extend_from_slice(&word16.to_be_bytes());
                    continue;
                }
                if r_count == 1 {
                    if t_vox == 1 {
                        let word16 = ((layer4::TAG_SINGLETON << 14) | (s_pos & 0x0FFF)) as u16;
                        dst.extend_from_slice(&word16.to_be_bytes());
                        continue;
                    }
                    if t_vox <= 257 {
                        let word16 =
                            ((layer4::TAG_SINGLETON << 14) | 0x1000 | (s_pos & 0x0FFF)) as u16;
                        dst.extend_from_slice(&word16.to_be_bytes());
                        dst.push((t_vox - 2) as u8);
                        continue;
                    }
                    let word16 =
                        ((layer4::TAG_SINGLETON << 14) | 0x2000 | (s_pos & 0x0FFF)) as u16;
                    dst.extend_from_slice(&word16.to_be_bytes());
                    let lm2 = (t_vox - 2) as u16;
                    dst.extend_from_slice(&lm2.to_be_bytes());
                    continue;
                }
                if r_count == 2 {
                    let (pos0, lm1_0) = runs[0];
                    let (adj_gap1, lm1_1) = runs[1];
                    if lm1_0 <= 15 && lm1_1 <= 15 {
                        let word16 =
                            ((layer4::TAG_SINGLETON << 14) | 0x3000 | (pos0 & 0x0FFF)) as u16;
                        dst.extend_from_slice(&word16.to_be_bytes());
                        dst.push(((lm1_0 << 4) | lm1_1) as u8);
                        Self::write_bounded_gap240(dst, adj_gap1);
                        continue;
                    }
                }

                let mut size44 = 2usize;
                let mut size53 = 2usize;
                let mut size62 = 2usize;
                let mut size71 = 2usize;
                let mut size_arr = if t_vox == r_count { 2usize } else { usize::MAX };
                let mut word_mask64 = 0u64;
                let mut cur_pos = 0usize;
                let mut first = true;
                for &(adj_gap, lm1) in runs {
                    let run_start = if first { adj_gap } else { cur_pos + adj_gap + 1 };
                    first = false;
                    let run_end = run_start + lm1;
                    let w_start = run_start >> 6;
                    let w_end = (run_end >> 6).min(63);
                    for w in w_start..=w_end {
                        word_mask64 |= 1u64 << w;
                    }
                    cur_pos = run_end + 1;

                    size44 += 1
                        + if adj_gap >= 15 {
                            Self::bounded_gap240_len(adj_gap - 15)
                        } else {
                            0
                        }
                        + if lm1 >= 15 {
                            Self::varint_len(lm1 - 15)
                        } else {
                            0
                        };
                    size53 += 1
                        + if adj_gap >= 31 {
                            Self::bounded_gap240_len(adj_gap - 31)
                        } else {
                            0
                        }
                        + if lm1 >= 7 {
                            Self::varint_len(lm1 - 7)
                        } else {
                            0
                        };
                    size62 += 1
                        + if adj_gap >= 63 {
                            Self::bounded_gap240_len(adj_gap - 63)
                        } else {
                            0
                        }
                        + if lm1 >= 3 {
                            Self::varint_len(lm1 - 3)
                        } else {
                            0
                        };
                    size71 += 1
                        + if adj_gap >= 127 {
                            Self::bounded_gap240_len(adj_gap - 127)
                        } else {
                            0
                        }
                        + if lm1 >= 1 {
                            Self::varint_len(lm1 - 1)
                        } else {
                            0
                        };
                    if t_vox == r_count {
                        size_arr += Self::bounded_gap240_len(adj_gap);
                    }
                }

                let best_run_bytes = size44.min(size53).min(size62).min(size71);
                let best_bytes = size_arr.min(best_run_bytes);
                let full_bitmap_bytes = (active_voxels + 7) >> 3;
                let non_zero_words = word_mask64.count_ones() as usize;
                let masked_bitmap_bytes = 8 + (non_zero_words << 3);
                let min_bitmap_total = 2 + full_bitmap_bytes.min(masked_bitmap_bytes);

                if min_bitmap_total + 12 < best_bytes
                    || best_bytes > 2 + full_bitmap_bytes
                    || r_count > 0x0FFF
                {
                    let total_words = active_voxels >> 6;
                    bitmap_words[..total_words].fill(0);
                    let mut c_pos = 0usize;
                    let mut is_first = true;
                    for &(adj_gap, lm1) in runs {
                        let r_start = if is_first { adj_gap } else { c_pos + adj_gap + 1 };
                        is_first = false;
                        let r_end = r_start + lm1;
                        c_pos = r_end + 1;
                        for idx in r_start..=r_end.min(active_voxels.saturating_sub(1)) {
                            bitmap_words[idx >> 6] |= 1u64 << (idx & 63);
                        }
                    }

                    if masked_bitmap_bytes < full_bitmap_bytes
                        && (1..=64).contains(&non_zero_words)
                    {
                        let word16 = ((layer4::TAG_BITMAP << 14) | non_zero_words) as u16;
                        dst.extend_from_slice(&word16.to_be_bytes());
                        dst.extend_from_slice(&word_mask64.to_be_bytes());
                        let mut m = word_mask64;
                        while m != 0 {
                            let w = m.trailing_zeros() as usize;
                            m &= m - 1;
                            dst.extend_from_slice(&bitmap_words[w].to_be_bytes());
                        }
                        continue;
                    }

                    let hdr_count = if full_bitmap_bytes == 512 {
                        512
                    } else {
                        0x1000 | full_bitmap_bytes
                    };
                    let word16 = ((layer4::TAG_BITMAP << 14) | hdr_count) as u16;
                    dst.extend_from_slice(&word16.to_be_bytes());
                    for w in 0..total_words {
                        dst.extend_from_slice(&bitmap_words[w].to_be_bytes());
                    }
                    continue;
                }

                if size_arr <= best_run_bytes {
                    let word16 = ((layer4::TAG_DELTA_ARRAY << 14) | (r_count & 0x3FFF)) as u16;
                    dst.extend_from_slice(&word16.to_be_bytes());
                    for &(adj_gap, _) in runs {
                        Self::write_bounded_gap240(dst, adj_gap);
                    }
                    continue;
                }

                let sub_mode = if size44 == best_run_bytes {
                    0usize
                } else if size53 == best_run_bytes {
                    1usize
                } else if size62 == best_run_bytes {
                    2usize
                } else {
                    3usize
                };
                let l_bits = 4 - sub_mode;
                let g_max = (1usize << (4 + sub_mode)) - 1;
                let l_max = (1usize << l_bits) - 1;
                let word16 = ((layer4::TAG_DELTA_RUN << 14)
                    | (sub_mode << 12)
                    | (r_count & 0x0FFF)) as u16;
                dst.extend_from_slice(&word16.to_be_bytes());
                for &(adj_gap, lm1) in runs {
                    let g_val = adj_gap.min(g_max);
                    let l_val = lm1.min(l_max);
                    dst.push(((g_val << l_bits) | l_val) as u8);
                    if g_val == g_max {
                        Self::write_bounded_gap240(dst, adj_gap - g_max);
                    }
                    if l_val == l_max {
                        Self::write_varint_vec(dst, lm1 - l_max);
                    }
                }
            }
        }

        fn extract_light_const_nibble(packed_2048: &[u8]) -> Option<u8> {
            if packed_2048.len() < 2048 {
                return None;
            }
            let b0 = packed_2048[0];
            let lo = b0 & 0x0F;
            if (b0 >> 4) != lo {
                return None;
            }
            if packed_2048[..2048].iter().all(|&b| b == b0) {
                Some(lo)
            } else {
                None
            }
        }

        fn encode_gray_light_planes(
            packed_2048: &[u8],
            curve_to_yzx: &[usize; SECTION_VOXELS],
        ) -> Vec<u8> {
            let mut yzx_nibbles = [0u8; SECTION_VOXELS];
            for i in 0..2048 {
                let b = packed_2048.get(i).copied().unwrap_or(0);
                yzx_nibbles[i << 1] = b & 0x0F;
                yzx_nibbles[(i << 1) | 1] = (b >> 4) & 0x0F;
            }
            let mut gray_curve = [0u8; SECTION_VOXELS];
            for i in 0..SECTION_VOXELS {
                let l = yzx_nibbles[curve_to_yzx[i]] & 0x0F;
                gray_curve[i] = l ^ (l >> 1);
            }

            let mut dst = Vec::with_capacity(256);
            dst.push(0); // reserve index 0 for plane_modes8
            let mut plane_modes8 = 0u8;
            let mut runs: Vec<(usize, usize)> = Vec::with_capacity(256);

            for plane in 0..4 {
                let mask = 1u8 << plane;
                runs.clear();
                let mut ones = 0usize;
                let mut cursor = 0usize;
                let mut i = 0usize;
                while i < SECTION_VOXELS {
                    if (gray_curve[i] & mask) == 0 {
                        i += 1;
                        continue;
                    }
                    let start = i;
                    i += 1;
                    while i < SECTION_VOXELS && (gray_curve[i] & mask) != 0 {
                        i += 1;
                    }
                    let gap = start - cursor;
                    let len = i - start;
                    ones += len;
                    runs.push((gap, len - 1));
                    cursor = i;
                }

                let mode: u8;
                if ones == 0 {
                    mode = 0;
                } else if ones == SECTION_VOXELS {
                    mode = 1;
                    dst.push(0xFF);
                } else if runs.len() == 1 {
                    let (start, len_m1) = runs[0];
                    let len = len_m1 + 1;
                    mode = 1;
                    if start + len == SECTION_VOXELS {
                        dst.push((0xE0 | ((start >> 8) & 0x0F)) as u8);
                        dst.push((start & 0xFF) as u8);
                    } else if start == 0 {
                        let end = len - 1;
                        dst.push((0xD0 | ((end >> 8) & 0x0F)) as u8);
                        dst.push((end & 0xFF) as u8);
                    } else {
                        dst.push((0xC0 | ((start >> 8) & 0x0F)) as u8);
                        dst.push((start & 0xFF) as u8);
                        Self::write_varint_vec(&mut dst, len_m1);
                    }
                } else {
                    let run_count = runs.len();
                    let hdr_bytes = 1
                        + if run_count >= 31 {
                            Self::varint_len(run_count - 31)
                        } else {
                            0
                        };
                    let mut size44 = hdr_bytes;
                    let mut size62 = hdr_bytes;
                    for (r, &(raw_gap, lm1)) in runs.iter().enumerate() {
                        let adj_gap = if r == 0 { raw_gap } else { raw_gap - 1 };
                        size44 += 1
                            + if adj_gap >= 15 {
                                Self::varint_len(adj_gap - 15)
                            } else {
                                0
                            }
                            + if lm1 >= 15 {
                                Self::varint_len(lm1 - 15)
                            } else {
                                0
                            };
                        size62 += 1
                            + if adj_gap >= 63 {
                                Self::varint_len(adj_gap - 63)
                            } else {
                                0
                            }
                            + if lm1 >= 3 {
                                Self::varint_len(lm1 - 3)
                            } else {
                                0
                            };
                    }
                    let best_len = size44.min(size62);
                    if best_len <= 512 {
                        mode = 1;
                        let split62 = size62 < size44;
                        let count_code = run_count.min(31);
                        dst.push(((if split62 { 0x20 } else { 0 }) | count_code) as u8);
                        if count_code == 31 {
                            Self::write_varint_vec(&mut dst, run_count - 31);
                        }
                        let l_bits = if split62 { 2 } else { 4 };
                        let g_max = if split62 { 63 } else { 15 };
                        let l_max = if split62 { 3 } else { 15 };
                        for (r, &(raw_gap, lm1)) in runs.iter().enumerate() {
                            let adj_gap = if r == 0 { raw_gap } else { raw_gap - 1 };
                            let g_val = adj_gap.min(g_max);
                            let l_val = lm1.min(l_max);
                            dst.push(((g_val << l_bits) | l_val) as u8);
                            if g_val == g_max {
                                Self::write_varint_vec(&mut dst, adj_gap - g_max);
                            }
                            if l_val == l_max {
                                Self::write_varint_vec(&mut dst, lm1 - l_max);
                            }
                        }
                    } else {
                        mode = 3;
                        let base = dst.len();
                        dst.resize(base + 512, 0);
                        for h in 0..SECTION_VOXELS {
                            if (gray_curve[h] & mask) != 0 {
                                dst[base + (h >> 3)] |= 1u8 << (h & 7);
                            }
                        }
                    }
                }
                plane_modes8 |= mode << (plane << 1);
            }
            dst[0] = plane_modes8;
            dst
        }

        #[inline]
        fn hash3(buf: &[u8], pos: usize) -> usize {
            let v = ((buf[pos] as u32) << 16)
                | ((buf[pos + 1] as u32) << 8)
                | (buf[pos + 2] as u32);
            ((v.wrapping_mul(0x1E35_A7BD)) >> 19) as usize
        }

        #[inline]
        fn hash4(buf: &[u8], pos: usize) -> usize {
            let v = read_u32_be(buf, pos);
            ((v.wrapping_mul(0x9E37_79B1)) >> 19) as usize
        }

        /// Encodes `[body_start..body_start + body_len)` of `chunk_frame` into an on-disk Mode B
        /// `LCD2` (`0x4C44` `"LD"`) Continuous Atomic Region Dictionary stream (`LCA-ICD-1.0` Section 6.2).
        pub fn encode_atomic_dict_body(
            chunk_frame: &[u8],
            body_start: usize,
            body_len: usize,
        ) -> Vec<u8> {
            if body_len == 0 {
                return Vec::new();
            }
            let mut intra_hash3 = [-1i32; 8192];
            let mut intra_hash4 = [-1i32; 8192];
            let mut intra_prev = [-1i32; 65536];

            let mut lit_buf = Vec::with_capacity(body_len);
            let mut cmd_buf = Vec::with_capacity(body_len >> 2);
            let mut off_buf = Vec::with_capacity(body_len >> 2);

            let mut anchor = 0usize;
            let mut p = 0usize;
            let mut last_offset = 0usize;

            while p + 3 <= body_len {
                let abs_p = body_start + p;
                let mut best_mode: isize = -1;
                let mut best_len = 0usize;
                let mut best_off = 0usize;
                let mut best_savings = 0isize;

                // 1. Check repeat offset (matchMode = 3, 0 offset bytes, minMatch = 3, requires litLen >= 1)
                if last_offset > 0 && p > anchor && p >= last_offset {
                    let max_rem = 256.min(body_len - p);
                    let src_base = abs_p - last_offset;
                    let mut rep_len = 0usize;
                    while rep_len < max_rem
                        && chunk_frame[abs_p + rep_len] == chunk_frame[src_base + rep_len]
                    {
                        rep_len += 1;
                    }
                    if rep_len >= 3 {
                        let sav = (rep_len as isize) - if rep_len >= 10 { 1 } else { 0 };
                        if sav > best_savings {
                            best_savings = sav;
                            best_mode = 3;
                            best_len = rep_len;
                            best_off = last_offset;
                        }
                    }
                }

                // 2. Check 3-byte short intra-chunk match (matchMode = 0, offset 1..=256)
                let h3 = Self::hash3(chunk_frame, abs_p);
                let cand3 = intra_hash3[h3];
                if cand3 >= 0 {
                    let dist3 = p - (cand3 as usize);
                    if (1..=256).contains(&dist3) {
                        let max_rem = 256.min(body_len - p);
                        let src_base = body_start + (cand3 as usize);
                        let mut m_len = 0usize;
                        while m_len < max_rem
                            && chunk_frame[abs_p + m_len] == chunk_frame[src_base + m_len]
                        {
                            m_len += 1;
                        }
                        if m_len >= 3 {
                            let sav = (m_len as isize) - 1 - if m_len >= 10 { 1 } else { 0 };
                            if sav > best_savings {
                                best_savings = sav;
                                best_mode = 0;
                                best_len = m_len;
                                best_off = dist3;
                            }
                        }
                    }
                }

                // 3. Check 4-byte intra-chunk matches (matchMode = 0 or 1, offset 1..=65792)
                let h4_opt = if p + 4 <= body_len {
                    Some(Self::hash4(chunk_frame, abs_p))
                } else {
                    None
                };
                if let Some(h4) = h4_opt {
                    let mut cand4 = intra_hash4[h4 & 8191];
                    let mut chain_steps = 0usize;
                    let max_rem = 512.min(body_len - p);
                    while cand4 >= 0 && chain_steps < 8 {
                        chain_steps += 1;
                        let c4 = cand4 as usize;
                        let dist4 = p.saturating_sub(c4);
                        if dist4 == 0 || dist4 > 65792 {
                            break;
                        }
                        let src_base = body_start + c4;
                        if best_len < max_rem
                            && chunk_frame[src_base + best_len] == chunk_frame[abs_p + best_len]
                            && read_u32_be(chunk_frame, src_base) == read_u32_be(chunk_frame, abs_p)
                        {
                            let mut m_len = 4usize;
                            while m_len < max_rem
                                && chunk_frame[abs_p + m_len] == chunk_frame[src_base + m_len]
                            {
                                m_len += 1;
                            }
                            let mode = if dist4 <= 256 { 0isize } else { 1isize };
                            let cost = (if mode == 0 { 1isize } else { 2isize })
                                + if m_len >= if mode == 0 { 10 } else { 11 } {
                                    1
                                } else {
                                    0
                                };
                            let sav = (m_len as isize) - cost;
                            if sav > best_savings {
                                best_savings = sav;
                                best_mode = mode;
                                best_len = m_len;
                                best_off = dist4;
                                if m_len >= 64 {
                                    break;
                                }
                            }
                        }
                        cand4 = intra_prev[c4 & 0xFFFF];
                    }
                }

                // 1-step lazy match lookahead
                if best_mode >= 0 && best_len < 14 && p + 5 <= body_len {
                    let next_abs = abs_p + 1;
                    let nh4 = Self::hash4(chunk_frame, next_abs);
                    let n_cand = intra_hash4[nh4 & 8191];
                    if n_cand >= 0 {
                        let nc = n_cand as usize;
                        let dist_n = (p + 1).saturating_sub(nc);
                        if (1..=65792).contains(&dist_n) {
                            let src_base = body_start + nc;
                            let max_rem = 64.min(body_len - (p + 1));
                            if max_rem > best_len + 1
                                && read_u32_be(chunk_frame, src_base)
                                    == read_u32_be(chunk_frame, next_abs)
                            {
                                let mut n_len = 4usize;
                                while n_len < max_rem
                                    && chunk_frame[next_abs + n_len]
                                        == chunk_frame[src_base + n_len]
                                {
                                    n_len += 1;
                                }
                                let n_sav =
                                    (n_len as isize) - if dist_n <= 256 { 1 } else { 2 };
                                if n_sav > best_savings + 1 {
                                    best_mode = -1;
                                }
                            }
                        }
                    }
                }

                if best_mode < 0 {
                    intra_hash3[h3] = p as i32;
                    if let Some(h4) = h4_opt {
                        let slot4 = h4 & 8191;
                        intra_prev[p & 0xFFFF] = intra_hash4[slot4];
                        intra_hash4[slot4] = p as i32;
                    }
                    p += 1;
                    continue;
                }

                let lit_len = p - anchor;
                if lit_len > 0 {
                    lit_buf.extend_from_slice(&chunk_frame[body_start + anchor..body_start + p]);
                }

                let min_match = if best_mode == 0 || best_mode == 3 { 3 } else { 4 };
                let len_m = best_len - min_match;
                let lit_code = lit_len.min(7);
                let len_code = len_m.min(7);
                cmd_buf.push(((lit_code << 5) | ((best_mode as usize) << 3) | len_code) as u8);

                if lit_code == 7 {
                    Self::write_varint_vec(&mut off_buf, lit_len - 7);
                }
                if len_code == 7 {
                    Self::write_varint_vec(&mut off_buf, len_m - 7);
                }
                if best_mode == 0 {
                    off_buf.push((best_off - 1) as u8);
                    last_offset = best_off;
                } else if best_mode == 1 {
                    let v = best_off - 257;
                    off_buf.push(((v >> 8) & 0xFF) as u8);
                    off_buf.push((v & 0xFF) as u8);
                    last_offset = best_off;
                }

                let match_end = p + best_len;
                let index_limit = match_end.min(body_len.saturating_sub(4));
                for k in p..index_limit {
                    let kh3 = Self::hash3(chunk_frame, body_start + k);
                    intra_hash3[kh3] = k as i32;
                    let kh4 = Self::hash4(chunk_frame, body_start + k) & 8191;
                    intra_prev[k & 0xFFFF] = intra_hash4[kh4];
                    intra_hash4[kh4] = k as i32;
                }
                p = match_end;
                anchor = p;
            }

            if anchor < body_len {
                lit_buf.extend_from_slice(&chunk_frame[body_start + anchor..body_start + body_len]);
            }

            let seq_count = cmd_buf.len();
            let lit_total = lit_buf.len();
            let off_total = off_buf.len();
            let meta_varints_len = Self::varint_len(seq_count)
                + Self::varint_len(lit_total)
                + Self::varint_len(off_total);
            let total_comp_len = 9 + meta_varints_len + lit_total + seq_count + off_total;

            if total_comp_len >= 9 + body_len {
                let raw_comp_len = 9 + body_len;
                let mut raw_out = Vec::with_capacity(raw_comp_len);
                raw_out.push(0x4C);
                raw_out.push(0x44);
                raw_out.push(0x04);
                raw_out.push(((raw_comp_len >> 16) & 0xFF) as u8);
                raw_out.push(((raw_comp_len >> 8) & 0xFF) as u8);
                raw_out.push((raw_comp_len & 0xFF) as u8);
                raw_out.push(((body_len >> 16) & 0xFF) as u8);
                raw_out.push(((body_len >> 8) & 0xFF) as u8);
                raw_out.push((body_len & 0xFF) as u8);
                raw_out.extend_from_slice(&chunk_frame[body_start..body_start + body_len]);
                return raw_out;
            }

            let mut out = Vec::with_capacity(total_comp_len);
            out.push(0x4C);
            out.push(0x44);
            out.push(0x00); // frame_flags = 0 (uncompressed lit/cmd/off streams)
            out.push(((total_comp_len >> 16) & 0xFF) as u8);
            out.push(((total_comp_len >> 8) & 0xFF) as u8);
            out.push((total_comp_len & 0xFF) as u8);
            out.push(((body_len >> 16) & 0xFF) as u8);
            out.push(((body_len >> 8) & 0xFF) as u8);
            out.push((body_len & 0xFF) as u8);
            Self::write_varint_vec(&mut out, seq_count);
            Self::write_varint_vec(&mut out, lit_total);
            Self::write_varint_vec(&mut out, off_total);
            out.extend_from_slice(&lit_buf);
            out.extend_from_slice(&cmd_buf);
            out.extend_from_slice(&off_buf);
            out
        }

        /// Wraps an uncompressed `ChunkFrame` (`chunk_frame`) into a Mode B (`codec1 = 1`) slab payload:
        /// `[0..header_len)` uncompressed header + `header_words = (header_len + 7) >> 3` Extended Hamming
        /// `(72, 64)` `SEC-DED` syndrome bytes + `0x4C44` `"LD"` `LCD2` compressed body.
        pub fn encode_mode_b_chunk_frame(chunk_frame: &[u8], header_len: usize) -> Vec<u8> {
            let header_words = (header_len + 7) >> 3;
            let body_len = chunk_frame.len().saturating_sub(header_len);
            let comp_body = if body_len == 0 {
                Vec::new()
            } else {
                Self::encode_atomic_dict_body(chunk_frame, header_len, body_len)
            };
            let mut out = Vec::with_capacity(header_len + header_words + comp_body.len());
            out.extend_from_slice(&chunk_frame[..header_len]);
            for w in 0..header_words {
                let word64 = layer2::read_header_word64_be(&out, 0, header_len, w);
                out.push(compute_hamming_secded8(word64));
            }
            out.extend_from_slice(&comp_body);
            out
        }

        fn serialize_dict_payload(states: &[String], biomes: &[String]) -> Vec<u8> {
            let mut payload = Vec::new();
            for s in states {
                let b = s.as_bytes();
                Self::write_varint_vec(&mut payload, b.len());
                payload.extend_from_slice(b);
                payload.extend_from_slice(&0u32.to_be_bytes());
            }
            for bm in biomes {
                let b = bm.as_bytes();
                Self::write_varint_vec(&mut payload, b.len());
                payload.extend_from_slice(b);
                payload.extend_from_slice(&0x5E9D_34FFu32.to_be_bytes());
            }
            payload
        }

        fn write_dict_segment(
            seg_buf: &mut [u8],
            states: &[String],
            biomes: &[String],
            link_trailer: u64,
        ) {
            seg_buf.fill(0);
            let payload = Self::serialize_dict_payload(states, biomes);
            let used = payload.len();
            seg_buf[0..4].copy_from_slice(&(states.len() as u32).to_be_bytes());
            seg_buf[4..6].copy_from_slice(&(biomes.len() as u16).to_be_bytes());
            seg_buf[6..8].copy_from_slice(&(used as u16).to_be_bytes());
            seg_buf[8..8 + used].copy_from_slice(&payload);

            let cap = seg_buf.len();
            let active_len = 8 + used;
            let word_count = (active_len + 7) >> 3;
            let syn_ceil = cap - 8;
            for w in 0..word_count {
                let word64 = layer2::read_header_word64_be(seg_buf, 0, active_len, w);
                seg_buf[syn_ceil - 1 - w] = compute_hamming_secded8(word64);
            }
            write_u64_be(seg_buf, cap - 8, link_trailer | 2u64);
        }

        /// Pre-encodes a `PumpkinChunkData` column into `PreEncodedChunk` on a worker thread
        /// (majority-sorted palettes, 4-curve Delta-Roaring vs Flat BitPack, 3D Biome RLE, and
        /// Constant/Gray-plane lighting) without locking the region dictionary (`leafanvil-ADR-004`).
        pub fn pre_encode_chunk(chunk: &PumpkinChunkData) -> PreEncodedChunk {
            let tables = layer4::curve_tables();
            let yzx_to_octant6 = &tables.yzx_to_octant6;
            let mut unique_states: Vec<String> = Vec::new();
            let mut unique_biomes: Vec<String> = Vec::new();
            let mut add_unique_state = |s: &str| {
                if !unique_states.iter().any(|x| x == s) {
                    unique_states.push(s.to_string());
                }
            };
            let mut add_unique_biome = |b: &str| {
                if !unique_biomes.iter().any(|x| x == b) {
                    unique_biomes.push(b.to_string());
                }
            };
            add_unique_state("minecraft:air");
            add_unique_biome("minecraft:plains");

            let mut sections = Vec::with_capacity(chunk.sub_chunks.len());
            let mut curve_palette_idx = [0usize; SECTION_VOXELS];
            let mut mapped_yzx = [0usize; SECTION_VOXELS];

            for sub in &chunk.sub_chunks {
                let raw_pal: Vec<&str> = if !sub.full_block_palette.is_empty() {
                    sub.full_block_palette.iter().map(|s| s.as_str()).collect()
                } else {
                    match &sub.block_states {
                        PumpkinBlockPalette::Uniform(s) => vec![s.as_str()],
                        PumpkinBlockPalette::Heterogeneous { palette, .. } => {
                            palette.iter().map(|s| s.as_str()).collect()
                        }
                    }
                };

                let raw_pal_len = raw_pal.len().max(1);
                let (is_uniform_block, sorted_states, octree_mask64, curve_id, block_mode, block_payload) =
                    match &sub.block_states {
                        PumpkinBlockPalette::Uniform(_) if raw_pal_len <= 1 => {
                            let s0 = raw_pal.first().copied().unwrap_or("minecraft:air").to_string();
                            add_unique_state(&s0);
                            (true, vec![s0], 0u64, layer4::CURVE_YZX, layer4::BLOCK_MODE_UNIFORM, Vec::new())
                        }
                        PumpkinBlockPalette::Uniform(u_str) => {
                            let s0 = raw_pal.first().copied().unwrap_or(u_str.as_str()).to_string();
                            add_unique_state(&s0);
                            (true, vec![s0], 0u64, layer4::CURVE_YZX, layer4::BLOCK_MODE_UNIFORM, Vec::new())
                        }
                        PumpkinBlockPalette::Heterogeneous { yzx_indices, .. } => {
                            let mut counts = vec![0usize; raw_pal_len];
                            for &idx in yzx_indices.iter().take(SECTION_VOXELS) {
                                let c_idx = (idx as usize).min(raw_pal_len - 1);
                                counts[c_idx] += 1;
                            }
                            let mut active_old: Vec<usize> = (0..raw_pal_len)
                                .filter(|&i| counts[i] > 0)
                                .collect();
                            if active_old.is_empty() {
                                active_old.push(0);
                            }
                            active_old.sort_by(|&a, &b| counts[b].cmp(&counts[a]).then(a.cmp(&b)));

                            if active_old.len() == 1 {
                                let s0 = raw_pal
                                    .get(active_old[0])
                                    .copied()
                                    .unwrap_or("minecraft:air")
                                    .to_string();
                                add_unique_state(&s0);
                                (true, vec![s0], 0u64, layer4::CURVE_YZX, layer4::BLOCK_MODE_UNIFORM, Vec::new())
                            } else {
                                let mut old_to_new = vec![0usize; raw_pal_len];
                                let mut sorted_pal = Vec::with_capacity(active_old.len());
                                for (new_idx, &old_idx) in active_old.iter().enumerate() {
                                    old_to_new[old_idx] = new_idx;
                                    let st = raw_pal
                                        .get(old_idx)
                                        .copied()
                                        .unwrap_or("minecraft:air")
                                        .to_string();
                                    add_unique_state(&st);
                                    sorted_pal.push(st);
                                }
                                let active_pal_size = sorted_pal.len();
                                let mut mask64 = 0u64;
                                for yzx in 0..SECTION_VOXELS {
                                    let old_i = (yzx_indices.get(yzx).copied().unwrap_or(0) as usize)
                                        .min(raw_pal_len - 1);
                                    let new_i = old_to_new[old_i];
                                    mapped_yzx[yzx] = new_i;
                                    if new_i != 0 {
                                        mask64 |= 1u64 << yzx_to_octant6[yzx];
                                    }
                                }

                                let curve0 = Self::select_optimal_curve_1pass(&mapped_yzx, 0);
                                let active_vox0 = Self::populate_compacted_curve_palette_idx(
                                    &mapped_yzx,
                                    curve0,
                                    mask64,
                                    &mut curve_palette_idx,
                                );
                                let mut roaring_buf = Vec::with_capacity(256);
                                Self::encode_minority_containers_single_pass(
                                    &curve_palette_idx,
                                    active_vox0,
                                    active_pal_size,
                                    &mut roaring_buf,
                                );
                                let mut best_curve = curve0;

                                if curve0 != layer4::CURVE_H3
                                    && mask64 != u64::MAX
                                    && mask64.count_ones() <= 28
                                {
                                    let active_h3 = Self::populate_compacted_curve_palette_idx(
                                        &mapped_yzx,
                                        layer4::CURVE_H3,
                                        mask64,
                                        &mut curve_palette_idx,
                                    );
                                    let mut alt_buf = Vec::with_capacity(roaring_buf.len());
                                    Self::encode_minority_containers_single_pass(
                                        &curve_palette_idx,
                                        active_h3,
                                        active_pal_size,
                                        &mut alt_buf,
                                    );
                                    if alt_buf.len() < roaring_buf.len() {
                                        roaring_buf = alt_buf;
                                        best_curve = layer4::CURVE_H3;
                                    }
                                }

                                let flat_bits = (32 - ((active_pal_size - 1) as u32).leading_zeros())
                                    .max(1) as usize;
                                let flat_byte_count = (SECTION_VOXELS * flat_bits + 7) >> 3;
                                if roaring_buf.len() <= flat_byte_count {
                                    let b_mode = if mask64 == u64::MAX {
                                        layer4::BLOCK_MODE_DELTA_ROARING_FULL_MASK
                                    } else {
                                        layer4::BLOCK_MODE_DELTA_ROARING
                                    };
                                    (false, sorted_pal, mask64, best_curve, b_mode, roaring_buf)
                                } else {
                                    let mut flat_buf = vec![0u8; flat_byte_count];
                                    let mut bit_pos = 0usize;
                                    for i in 0..SECTION_VOXELS {
                                        let val = mapped_yzx[i];
                                        for b in 0..flat_bits {
                                            if (val & (1 << b)) != 0 {
                                                flat_buf[bit_pos >> 3] |= 1u8 << (bit_pos & 7);
                                            }
                                            bit_pos += 1;
                                        }
                                    }
                                    (
                                        false,
                                        sorted_pal,
                                        mask64,
                                        layer4::CURVE_YZX,
                                        layer4::BLOCK_MODE_FLAT_BITPACK,
                                        flat_buf,
                                    )
                                }
                            }
                        }
                    };

                let b_pal = if sub.biomes.palette.is_empty() {
                    vec!["minecraft:plains".to_string()]
                } else {
                    sub.biomes.palette.clone()
                };
                for bm in &b_pal {
                    add_unique_biome(bm);
                }
                let mut biome_rle_payload = Vec::new();
                if b_pal.len() > 1 {
                    let max_run = if b_pal.len() <= 8 { 32usize } else { 64usize };
                    let mut runs: Vec<(usize, usize)> = Vec::with_capacity(16);
                    let yzx0 = 0usize;
                    let mut cur_val = sub.biomes.cells_yzx[yzx0].min(b_pal.len() - 1);
                    let mut cur_len = 1usize;
                    for cursor in 1..BIOME_CELLS {
                        let yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >> 4) & 3);
                        let v = sub.biomes.cells_yzx[yzx].min(b_pal.len() - 1);
                        if v == cur_val && cur_len < max_run {
                            cur_len += 1;
                        } else {
                            runs.push((cur_val, cur_len - 1));
                            cur_val = v;
                            cur_len = 1;
                        }
                    }
                    runs.push((cur_val, cur_len - 1));
                    Self::write_varint_vec(&mut biome_rle_payload, runs.len());
                    if b_pal.len() <= 8 {
                        for (p_idx, lm1) in runs {
                            biome_rle_payload.push((((p_idx & 7) << 5) | (lm1 & 0x1F)) as u8);
                        }
                    } else {
                        for (p_idx, lm1) in runs {
                            Self::write_varint_vec(&mut biome_rle_payload, p_idx);
                            biome_rle_payload.push(lm1 as u8);
                        }
                    }
                }

                let mut sky_const = None;
                let mut blk_const = None;
                let mut sky_bytes = Vec::new();
                let mut blk_bytes = Vec::new();
                let sky_mode = match sub.sky_light {
                    None => layer4::LIGHT_MODE_ABSENT,
                    Some(ref sky) => {
                        if let Some(c) = Self::extract_light_const_nibble(sky) {
                            sky_const = Some(c);
                            layer4::LIGHT_MODE_CONST
                        } else {
                            let gray = Self::encode_gray_light_planes(
                                sky,
                                &tables.curve_to_yzx[layer4::CURVE_Y_H2],
                            );
                            if gray.len() < 2048 {
                                sky_bytes = gray;
                                layer4::LIGHT_MODE_GRAY_PLANES
                            } else {
                                if sky.len() >= 2048 {
                                    sky_bytes.extend_from_slice(&sky[..2048]);
                                } else {
                                    sky_bytes.resize(2048, 0xFF);
                                }
                                layer4::LIGHT_MODE_RAW
                            }
                        }
                    }
                };
                let blk_mode = match sub.block_light {
                    None => layer4::LIGHT_MODE_ABSENT,
                    Some(ref blk) => {
                        if let Some(c) = Self::extract_light_const_nibble(blk) {
                            blk_const = Some(c);
                            layer4::LIGHT_MODE_CONST
                        } else {
                            let gray = Self::encode_gray_light_planes(
                                blk,
                                &tables.curve_to_yzx[layer4::CURVE_H3],
                            );
                            if gray.len() < 2048 {
                                blk_bytes = gray;
                                layer4::LIGHT_MODE_GRAY_PLANES
                            } else {
                                if blk.len() >= 2048 {
                                    blk_bytes.extend_from_slice(&blk[..2048]);
                                } else {
                                    blk_bytes.resize(2048, 0);
                                }
                                layer4::LIGHT_MODE_RAW
                            }
                        }
                    }
                };

                let mut light_payload =
                    Vec::with_capacity(1 + sky_bytes.len() + blk_bytes.len());
                if sky_const.is_some() || blk_const.is_some() {
                    let sc = sky_const.unwrap_or(0) & 0x0F;
                    let bc = blk_const.unwrap_or(0) & 0x0F;
                    light_payload.push((bc << 4) | sc);
                }
                light_payload.extend_from_slice(&sky_bytes);
                light_payload.extend_from_slice(&blk_bytes);

                sections.push(PreEncodedSection {
                    section_y: sub.section_y,
                    is_uniform_block,
                    curve_id,
                    block_mode,
                    octree_mask64,
                    sorted_states,
                    block_payload,
                    biome_palette: b_pal,
                    biome_rle_payload,
                    sky_mode,
                    blk_mode,
                    sky_const,
                    blk_const,
                    light_payload,
                });
            }

            PreEncodedChunk {
                chunk_x: chunk.chunk_x,
                chunk_z: chunk.chunk_z,
                min_section_y: chunk.min_section_y,
                section_count: chunk.section_count,
                data_version: chunk.data_version,
                last_update: chunk.last_update,
                inhabited_time: chunk.inhabited_time,
                status_ordinal: chunk.status_ordinal,
                sections,
                unique_states,
                unique_biomes,
            }
        }

        /// Binds `PreEncodedChunk` batch to the region's Layer 2 dictionary, compresses each
        /// `ChunkFrame` in Mode B (`0x4C44` `"LD"` `LCD2`), allocates `256 B` slabs via
        /// `FreeSlabAllocator` (reclaiming overwritten `CoW` slabs), and returns `LcaCommitReceipt`
        /// with exact dirty byte ranges for positional `pwrite` (`write_all_at`).
        pub fn commit_pre_encoded_chunks_cow(
            region_bytes: &mut Vec<u8>,
            allocator_opt: &mut Option<FreeSlabAllocator>,
            chunks: &[PreEncodedChunk],
        ) -> LcaResult<LcaCommitReceipt> {
            if chunks.is_empty() {
                return Ok(LcaCommitReceipt {
                    dict_dirty_ranges: Vec::new(),
                    chunk_commits: Vec::new(),
                    footer_canary_offset: region_bytes.len().saturating_sub(FOOTER_SIZE),
                });
            }
            let first = &chunks[0];
            if region_bytes.is_empty() {
                *region_bytes = Self::create_empty_region(
                    first.chunk_x >> 5,
                    first.chunk_z >> 5,
                    first.min_section_y,
                    first.section_count.max(1),
                    first.data_version.max(3465),
                );
            }

            let sb = parse_and_validate_superblock(region_bytes)?;
            let mut dict = ChainedRegionDictionary::parse(region_bytes, &sb)?;
            if allocator_opt.is_none() {
                *allocator_opt = Some(FreeSlabAllocator::reconstruct(
                    region_bytes,
                    &sb,
                    &dict.segments,
                ));
            }

            let mut dict_dirty_ranges: Vec<(usize, usize)> = Vec::new();
            let mut new_states: Vec<String> = Vec::new();
            let mut new_biomes: Vec<String> = Vec::new();
            for ch in chunks {
                for s in &ch.unique_states {
                    if !dict.canonical_states.iter().any(|x| x == s)
                        && !new_states.iter().any(|x| x == s)
                    {
                        new_states.push(s.clone());
                    }
                }
                for b in &ch.unique_biomes {
                    if !dict.biomes.iter().any(|x| x == b) && !new_biomes.iter().any(|x| x == b) {
                        new_biomes.push(b.clone());
                    }
                }
            }

            if !new_states.is_empty() || !new_biomes.is_empty() {
                let &(tail_off, tail_cap) = dict.segments.last().ok_or_else(|| {
                    LcaError::CorruptRegionEntry("Empty dictionary segment list".to_string())
                })?;
                let tail_state_count = read_u32_be(region_bytes, tail_off) as usize;
                let tail_biome_count = read_u16_be(region_bytes, tail_off + 4) as usize;
                let tail_used = read_u16_be(region_bytes, tail_off + 6) as usize;
                let tail_states_start =
                    dict.canonical_states.len().saturating_sub(tail_state_count);
                let tail_biomes_start = dict.biomes.len().saturating_sub(tail_biome_count);
                let mut combined_states: Vec<String> =
                    dict.canonical_states[tail_states_start..].to_vec();
                combined_states.extend(new_states.iter().cloned());
                let mut combined_biomes: Vec<String> = dict.biomes[tail_biomes_start..].to_vec();
                combined_biomes.extend(new_biomes.iter().cloned());

                let existing_tail_payload = Self::serialize_dict_payload(
                    &dict.canonical_states[tail_states_start..],
                    &dict.biomes[tail_biomes_start..],
                );
                let tail_has_pattern = existing_tail_payload.len() != tail_used;
                let combined_payload =
                    Self::serialize_dict_payload(&combined_states, &combined_biomes);
                let combined_used = combined_payload.len();
                let combined_words = (8 + combined_used + 7) >> 3;

                if !tail_has_pattern
                    && combined_used <= 60000
                    && 8 + combined_used + combined_words + 16 <= tail_cap
                {
                    Self::write_dict_segment(
                        &mut region_bytes[tail_off..tail_off + tail_cap],
                        &combined_states,
                        &combined_biomes,
                        2u64,
                    );
                    dict_dirty_ranges.push((tail_off, tail_cap));
                } else {
                    let payload = Self::serialize_dict_payload(&new_states, &new_biomes);
                    let used = payload.len();
                    let word_count = (8 + used + 7) >> 3;
                    let min_cap = 8 + used + word_count + 64;
                    let cont_slabs = ((min_cap + 255) >> SLAB_SHIFT).max(64);
                    let cont_cap = cont_slabs << SLAB_SHIFT;
                    let alloc = allocator_opt.as_mut().unwrap();
                    let new_seg_slab = alloc.allocate(cont_slabs);
                    let new_seg_off = new_seg_slab << SLAB_SHIFT;
                    let rel_slabs =
                        (new_seg_slab as i32) - ((tail_off >> SLAB_SHIFT) as i32);
                    let link_trailer = (((rel_slabs as u32) as u64) << 32)
                        | ((cont_slabs as u64) << 8)
                        | 3u64;
                    write_u64_be(region_bytes, tail_off + tail_cap - 8, link_trailer);
                    dict_dirty_ranges.push((tail_off + tail_cap - 8, 8));

                    let req_len = (alloc.active_end_slab << SLAB_SHIFT) + FOOTER_SIZE;
                    if region_bytes.len() < req_len {
                        region_bytes.resize(req_len, 0);
                    }
                    Self::write_dict_segment(
                        &mut region_bytes[new_seg_off..new_seg_off + cont_cap],
                        &new_states,
                        &new_biomes,
                        2u64,
                    );
                    write_u64_be(
                        region_bytes,
                        alloc.active_end_slab << SLAB_SHIFT,
                        LCA_FOOTER_CANARY_MAGIC,
                    );
                    dict_dirty_ranges.push((new_seg_off, cont_cap));
                }
                dict = ChainedRegionDictionary::parse(region_bytes, &sb)?;
            }

            let mut state_map: std::collections::HashMap<&str, usize> =
                std::collections::HashMap::with_capacity(dict.canonical_states.len() * 2);
            for (idx, bname) in dict.base_block_names.iter().enumerate() {
                state_map.entry(bname.as_str()).or_insert(idx);
            }
            for (idx, cname) in dict.canonical_states.iter().enumerate() {
                state_map.insert(cname.as_str(), idx);
            }
            let mut biome_map: std::collections::HashMap<&str, usize> =
                std::collections::HashMap::with_capacity(dict.biomes.len() * 2);
            for (idx, bm) in dict.biomes.iter().enumerate() {
                biome_map.insert(bm.as_str(), idx);
            }

            let state_id_of = |s: &str| -> usize {
                state_map
                    .get(s)
                    .copied()
                    .or_else(|| state_map.get(strip_block_properties(s)).copied())
                    .unwrap_or(0)
            };
            let biome_id_of = |b: &str| -> usize { biome_map.get(b).copied().unwrap_or(0) };
            let default_biome_id = biome_id_of("minecraft:plains");
            let section_dir_end = 24 + (sb.section_count << 1);

            let mut chunk_commits = Vec::with_capacity(chunks.len());
            for chunk in chunks {
                let rx = (chunk.chunk_x & 31) as usize;
                let rz = (chunk.chunk_z & 31) as usize;
                let chunk_idx = rx | (rz << 5);

                let mut section_entries = vec![0u16; sb.section_count];
                let mut section_blobs: Vec<(usize, Vec<u8>)> =
                    Vec::with_capacity(chunk.sections.len());

                for sec in &chunk.sections {
                    let sec_idx_isize = (sec.section_y - sb.min_section_y) as isize;
                    if sec_idx_isize < 0 || (sec_idx_isize as usize) >= sb.section_count {
                        continue;
                    }
                    let sec_idx = sec_idx_isize as usize;

                    if sec.is_uniform_block {
                        let gid = state_id_of(
                            sec.sorted_states
                                .first()
                                .map(|s| s.as_str())
                                .unwrap_or("minecraft:air"),
                        );
                        let b_single = sec.biome_palette.len() <= 1
                            && biome_id_of(
                                sec.biome_palette
                                    .first()
                                    .map(|s| s.as_str())
                                    .unwrap_or("minecraft:plains"),
                            ) == default_biome_id;
                        let blk_none = sec.blk_mode == layer4::LIGHT_MODE_ABSENT;
                        let sky_inline_ok = sec.sky_mode == layer4::LIGHT_MODE_ABSENT
                            || sec.sky_const == Some(15);
                        if gid < 0x1FFF && b_single && blk_none && sky_inline_ok {
                            let sky_bits = if sec.sky_const == Some(15) { 1u16 } else { 0u16 };
                            section_entries[sec_idx] =
                                0x8000u16 | (sky_bits << 13) | (gid as u16 & 0x1FFF);
                            continue;
                        }
                    }

                    let mut sec_buf = Vec::with_capacity(
                        16 + sec.block_payload.len()
                            + sec.biome_rle_payload.len()
                            + sec.light_payload.len(),
                    );
                    let sec_hdr = ((sec.curve_id as u8 & 3) << 6)
                        | ((sec.block_mode & 3) << 4)
                        | ((sec.sky_mode & 3) << 2)
                        | (sec.blk_mode & 3);
                    sec_buf.push(sec_hdr);

                    if sec.block_mode == layer4::BLOCK_MODE_DELTA_ROARING
                        || sec.block_mode == layer4::BLOCK_MODE_FLAT_BITPACK
                    {
                        sec_buf.extend_from_slice(&sec.octree_mask64.to_be_bytes());
                    }

                    let pal_len = sec.sorted_states.len().max(1);
                    Self::write_varint_vec(&mut sec_buf, pal_len);
                    for s in &sec.sorted_states {
                        Self::write_varint_vec(&mut sec_buf, state_id_of(s));
                    }
                    sec_buf.extend_from_slice(&sec.block_payload);

                    // Biomes
                    let b_len = sec.biome_palette.len();
                    if b_len <= 1 {
                        let b_id = biome_id_of(
                            sec.biome_palette
                                .first()
                                .map(|s| s.as_str())
                                .unwrap_or("minecraft:plains"),
                        );
                        if b_id == default_biome_id {
                            Self::write_varint_vec(&mut sec_buf, 0);
                        } else {
                            Self::write_varint_vec(&mut sec_buf, 1);
                            Self::write_varint_vec(&mut sec_buf, b_id);
                        }
                    } else {
                        Self::write_varint_vec(&mut sec_buf, b_len);
                        for bm in &sec.biome_palette {
                            Self::write_varint_vec(&mut sec_buf, biome_id_of(bm));
                        }
                        sec_buf.extend_from_slice(&sec.biome_rle_payload);
                    }

                    sec_buf.extend_from_slice(&sec.light_payload);
                    section_blobs.push((sec_idx, sec_buf));
                }

                let mut total_sec_bytes = 0usize;
                for (_, blob) in &section_blobs {
                    total_sec_bytes += (blob.len() + 3) & !3;
                }
                let scale_4b = total_sec_bytes >= 32000;
                let unit_shift = if scale_4b { 2 } else { 0 };
                let chunk_flags8 = (if scale_4b { 4u8 } else { 0u8 })
                    | (((default_biome_id as u8 + 1) & 0x1F) << 3);

                let ptr_pos = POINTER_DIRECTORY_OFFSET + (chunk_idx << 3);
                let old_raw = read_u64_be(region_bytes, ptr_pos);
                let old_ptr = ChunkPointer64::unpack(old_raw);
                let next_gen = if old_ptr.is_present() {
                    old_ptr.generation8.wrapping_add(1)
                } else {
                    1
                };
                let effective_dv = if old_ptr.is_present()
                    && old_ptr.byte_offset() + 4 <= region_bytes.len()
                {
                    let old_dv = (read_u32_be(region_bytes, old_ptr.byte_offset()) & 0xFFFF) as i32;
                    if old_dv > 0 {
                        old_dv
                    } else {
                        chunk.data_version
                    }
                } else {
                    chunk.data_version
                };

                let mut uncomp_frame =
                    Vec::with_capacity(section_dir_end + total_sec_bytes);
                uncomp_frame.resize(section_dir_end, 0);
                let raw_dv_word = CHUNK_FRAME_SELF_TAG_MAGIC
                    | (((chunk_idx as u32) & 0x03FF) << 16)
                    | ((effective_dv as u32) & 0xFFFF);
                uncomp_frame[0..4].copy_from_slice(&raw_dv_word.to_be_bytes());
                uncomp_frame[4..12].copy_from_slice(&(chunk.last_update as u64).to_be_bytes());
                uncomp_frame[12..20].copy_from_slice(&(chunk.inhabited_time as u64).to_be_bytes());
                uncomp_frame[20] = chunk.status_ordinal;
                uncomp_frame[21] = chunk_flags8;
                uncomp_frame[22..24].copy_from_slice(&0u16.to_be_bytes());

                for (sec_idx, blob) in section_blobs {
                    if scale_4b {
                        while ((uncomp_frame.len() - section_dir_end) & 3) != 0 {
                            uncomp_frame.push(0);
                        }
                    }
                    let rel_units = (uncomp_frame.len() - section_dir_end) >> unit_shift;
                    section_entries[sec_idx] = (rel_units as u16) + 1;
                    uncomp_frame.extend_from_slice(&blob);
                }
                for (i, &entry16) in section_entries.iter().enumerate() {
                    let off = 24 + (i << 1);
                    uncomp_frame[off..off + 2].copy_from_slice(&entry16.to_be_bytes());
                }

                let mode_b_frame =
                    Self::encode_mode_b_chunk_frame(&uncomp_frame, section_dir_end);
                let (encoded_frame, is_mode_b) = if mode_b_frame.len() < uncomp_frame.len() {
                    (mode_b_frame, true)
                } else {
                    (uncomp_frame, false)
                };

                let padded_bytes = (encoded_frame.len() + 255) & !255;
                let needed_slabs = (padded_bytes >> SLAB_SHIFT).max(1);
                let alloc = allocator_opt.as_mut().unwrap();
                let new_slab35 = alloc.allocate(needed_slabs);
                if old_ptr.is_present() {
                    let old_slabs = (old_ptr.exact_byte_len20 + 255) >> SLAB_SHIFT;
                    alloc.free(old_ptr.slab_offset35, old_slabs);
                }

                let new_slab_byte_off = new_slab35 << SLAB_SHIFT;
                let req_total_len = (alloc.active_end_slab << SLAB_SHIFT) + FOOTER_SIZE;
                if region_bytes.len() < req_total_len {
                    region_bytes.resize(req_total_len, 0);
                } else if region_bytes.len() > req_total_len {
                    region_bytes.truncate(req_total_len);
                }

                region_bytes[new_slab_byte_off..new_slab_byte_off + encoded_frame.len()]
                    .copy_from_slice(&encoded_frame);
                if padded_bytes > encoded_frame.len() {
                    region_bytes
                        [new_slab_byte_off + encoded_frame.len()..new_slab_byte_off + padded_bytes]
                        .fill(0);
                }
                let footer_off = alloc.active_end_slab << SLAB_SHIFT;
                write_u64_be(region_bytes, footer_off, LCA_FOOTER_CANARY_MAGIC);

                let new_raw = ChunkPointer64::pack(
                    new_slab35,
                    is_mode_b,
                    encoded_frame.len(),
                    next_gen,
                );
                write_u64_be(region_bytes, ptr_pos, new_raw);
                region_bytes[sb.secded_table_offset + chunk_idx] = compute_hamming_secded8(new_raw);
                chunk_commits.push((
                    chunk_idx,
                    new_slab_byte_off,
                    padded_bytes,
                    ChunkPointer64::unpack(new_raw),
                ));
            }

            let checksum = compute_superblock_checksum64(region_bytes, 0);
            write_u64_be(region_bytes, 0x38, checksum);
            let footer_canary_offset = region_bytes.len() - FOOTER_SIZE;

            Ok(LcaCommitReceipt {
                dict_dirty_ranges,
                chunk_commits,
                footer_canary_offset,
            })
        }

        /// Encodes a `PumpkinChunkData` column into a Mode B (`0x4C44` `"LD"` `LCD2`) `.lca`
        /// `ChunkFrame` + 4-curve Delta-Roaring `SectionFrame`s and commits it via $O(1)$
        /// `256 B`-aligned Copy-on-Write (`CoW`) slab allocation.
        pub fn encode_and_save_chunk_cow(
            region_bytes: &mut Vec<u8>,
            chunk: &PumpkinChunkData,
        ) -> LcaResult<ChunkPointer64> {
            let pre = Self::pre_encode_chunk(chunk);
            let mut alloc = None;
            let receipt = Self::commit_pre_encoded_chunks_cow(
                region_bytes,
                &mut alloc,
                std::slice::from_ref(&pre),
            )?;
            Ok(receipt.chunk_commits[0].3)
        }

        /// Sub-chunk $Y$-window surface probe (`leafanvil-ADR-004` Section 3 Axis 4, `REQ-RTP-S-005`).
        /// Jumps directly to `sectionOffsets[sy - minSectionY]` for $Y \in [\text{min\_y} .. \text{max\_y}]$,
        /// skipping empty (`0x0000`) and inline-air (`0x8000 | 0`) sections in $O(1)$ without decoding
        /// sections outside the requested vertical window.
        pub fn read_terrain_probe(
            region_bytes: &[u8],
            cx: i32,
            cz: i32,
            min_y: i32,
            max_y: i32,
        ) -> LcaResult<Vec<PumpkinSubChunk>> {
            let sb = parse_and_validate_superblock(region_bytes)?;
            let dict = ChainedRegionDictionary::parse_strict(region_bytes, &sb)?;
            Self::read_terrain_probe_with_dict(region_bytes, &sb, &dict, cx, cz, min_y, max_y)
        }

        pub fn read_terrain_probe_with_dict(
            region_bytes: &[u8],
            sb: &Superblock,
            dict: &ChainedRegionDictionary,
            cx: i32,
            cz: i32,
            min_y: i32,
            max_y: i32,
        ) -> LcaResult<Vec<PumpkinSubChunk>> {
            let min_sec_y = min_y >> 4;
            let max_sec_y = max_y >> 4;
            let chunk_opt = Self::read_chunk_with_dict(
                region_bytes,
                sb,
                dict,
                cx,
                cz,
                Some((min_sec_y, max_sec_y)),
            )?;
            Ok(chunk_opt.map_or_else(Vec::new, |c| c.sub_chunks))
        }

        /// Performs an $O(1)$ `256 B`-aligned Copy-on-Write (`CoW`) chunk slab save in `region_bytes`,
        /// updating `pointerDirectory[idx]`, `secDedTable[idx]`, `"LCA1_END"` footer canary, and the
        /// 64-bit Superblock `headerChecksum` (`leafanvil-ADR-004` Section 1.2 & Section 3 Axis 5).
        pub fn save_chunk_cow(
            region_bytes: &mut Vec<u8>,
            cx: i32,
            cz: i32,
        ) -> LcaResult<ChunkPointer64> {
            let sb = parse_and_validate_superblock(region_bytes)?;
            let rx = (cx & 31) as usize;
            let rz = (cz & 31) as usize;
            let chunk_idx = rx | (rz << 5);
            let ptr_pos = POINTER_DIRECTORY_OFFSET + (chunk_idx << 3);
            let old_raw = read_u64_be(region_bytes, ptr_pos);
            let old_ptr = ChunkPointer64::unpack(old_raw);
            if !old_ptr.is_present() {
                return Err(LcaError::CorruptRegionEntry(
                    "Chunk not present for CoW update".to_string(),
                ));
            }
            let old_off = old_ptr.byte_offset();
            let slab_payload =
                region_bytes[old_off..old_off + old_ptr.exact_byte_len20].to_vec();
            let padded_bytes = (slab_payload.len() + 255) & !255;
            let new_slab_byte_off = region_bytes.len() - FOOTER_SIZE;
            let new_slab35 = new_slab_byte_off >> SLAB_SHIFT;

            region_bytes.truncate(new_slab_byte_off);
            region_bytes.extend_from_slice(&slab_payload);
            if padded_bytes > slab_payload.len() {
                region_bytes.resize(new_slab_byte_off + padded_bytes, 0);
            }
            region_bytes.extend_from_slice(&LCA_FOOTER_CANARY_MAGIC.to_be_bytes());

            let next_gen = old_ptr.generation8.wrapping_add(1);
            let new_raw = ChunkPointer64::pack(
                new_slab35,
                old_ptr.codec1 == 1,
                slab_payload.len(),
                next_gen,
            );
            write_u64_be(region_bytes, ptr_pos, new_raw);
            region_bytes[sb.secded_table_offset + chunk_idx] = compute_hamming_secded8(new_raw);
            let checksum = compute_superblock_checksum64(region_bytes, 0);
            write_u64_be(region_bytes, 0x38, checksum);

            Ok(ChunkPointer64::unpack(new_raw))
        }
    }
}
