//! Cross-language golden `.lca` fixture, 64-bit FNV-1a digest parity, `CBIT T1..T8` tripwire,
//! 4-tier `SEC-DED` self-healing, and `PumpkinMC` Direct `SectionFrame` Bridge verification suite
//! (`leafanvil-ADR-004`, `REQ-RTP-S-004`, `REQ-RTP-S-005`).

use lca_format::cbit::heal_region_bit_rot;
use lca_format::layer1::{parse_and_validate_superblock, ChunkPointer64, FreeSlabAllocator};
use lca_format::layer2::ChainedRegionDictionary;
use lca_format::pumpkin_bridge::{LcaPumpkinBridge, PumpkinWorldChunkConfig};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone)]
struct SectionManifest {
    section_y: i32,
    inline_uniform: bool,
    octree_occupancy_mask64: i64,
    voxel_fnv1a64: u64,
    biome_fnv1a64: u64,
    sky_light_fnv1a64: u64,
    block_light_fnv1a64: u64,
}

#[derive(Debug, Clone)]
struct ChunkManifest {
    chunk_x: i32,
    chunk_z: i32,
    data_version: i32,
    generation_token11: u8,
    raw_pointer_word64: u64,
    pointer_secded_syndrome8: u8,
    sections: Vec<SectionManifest>,
}

#[derive(Debug, Clone)]
struct CbitVectorManifest {
    vector_id: String,
    tripwire_rule: String,
    mutated_byte_offset: usize,
    original_byte: u8,
    mutated_byte: u8,
    expect_self_healed: bool,
    expect_rejected: bool,
}

#[derive(Debug, Clone)]
struct BundleManifest {
    fixture_id: String,
    data_version: i32,
    mode_a_byte_length: usize,
    mode_b_byte_length: usize,
    chunks: Vec<ChunkManifest>,
    cbit_vectors: Vec<CbitVectorManifest>,
}

fn extract_str_field<'a>(block: &'a str, key: &str) -> &'a str {
    let pat = format!("\"{}\": \"", key);
    let start = block
        .find(&pat)
        .unwrap_or_else(|| panic!("Missing string key {}", key))
        + pat.len();
    let end = block[start..].find('"').unwrap() + start;
    &block[start..end]
}

fn extract_num_field(block: &str, key: &str) -> i64 {
    let pat = format!("\"{}\": ", key);
    let start = block
        .find(&pat)
        .unwrap_or_else(|| panic!("Missing numeric key {}", key))
        + pat.len();
    let tail = &block[start..];
    let end = tail
        .find(|c: char| !c.is_ascii_digit() && c != '-')
        .unwrap_or(tail.len());
    tail[..end].parse::<i64>().unwrap()
}

fn extract_bool_field(block: &str, key: &str) -> bool {
    let pat = format!("\"{}\": ", key);
    let start = block
        .find(&pat)
        .unwrap_or_else(|| panic!("Missing bool key {}", key))
        + pat.len();
    block[start..].starts_with("true")
}

fn extract_hex_u64(block: &str, key: &str) -> u64 {
    let raw = extract_str_field(block, key);
    let stripped = raw.strip_prefix("0x").unwrap_or(raw);
    u64::from_str_radix(stripped, 16).unwrap()
}

fn parse_golden_manifest(json: &str) -> Vec<BundleManifest> {
    let mut bundles = Vec::new();
    let parts: Vec<&str> = json.split("\"fixtureId\": \"").skip(1).collect();
    for part in parts {
        let id_end = part.find('"').unwrap();
        let fixture_id = part[..id_end].to_string();
        let data_version = extract_num_field(part, "dataVersion") as i32;
        let mode_a_byte_length = extract_num_field(part, "modeAByteLength") as usize;
        let mode_b_byte_length = extract_num_field(part, "modeBByteLength") as usize;

        let chunks_split_pos = part.find("\"cbitVectors\": [").unwrap();
        let chunks_region = &part[..chunks_split_pos];
        let vectors_region = &part[chunks_split_pos..];

        let mut chunks = Vec::new();
        for c_part in chunks_region.split("\"chunkX\": ").skip(1) {
            let cx_end = c_part
                .find(|c: char| !c.is_ascii_digit() && c != '-')
                .unwrap();
            let chunk_x = c_part[..cx_end].parse::<i32>().unwrap();
            let chunk_z = extract_num_field(c_part, "chunkZ") as i32;
            let c_dv = extract_num_field(c_part, "dataVersion") as i32;
            let generation_token11 = extract_num_field(c_part, "generationToken11") as u8;
            let raw_pointer_word64 = extract_hex_u64(c_part, "rawPointerWord64");
            let pointer_secded_syndrome8 =
                extract_num_field(c_part, "pointerSecDedSyndrome8") as u8;

            let mut sections = Vec::new();
            for s_part in c_part.split("\"sectionY\": ").skip(1) {
                let sy_end = s_part
                    .find(|c: char| !c.is_ascii_digit() && c != '-')
                    .unwrap();
                let section_y = s_part[..sy_end].parse::<i32>().unwrap();
                let inline_uniform = extract_bool_field(s_part, "inlineUniform");
                let octree_occupancy_mask64 =
                    extract_hex_u64(s_part, "octreeOccupancyMask64") as i64;
                let voxel_fnv1a64 = extract_hex_u64(s_part, "voxelFnv1a64");
                let biome_fnv1a64 = extract_hex_u64(s_part, "biomeFnv1a64");
                let sky_light_fnv1a64 = extract_hex_u64(s_part, "skyLightFnv1a64");
                let block_light_fnv1a64 = extract_hex_u64(s_part, "blockLightFnv1a64");
                sections.push(SectionManifest {
                    section_y,
                    inline_uniform,
                    octree_occupancy_mask64,
                    voxel_fnv1a64,
                    biome_fnv1a64,
                    sky_light_fnv1a64,
                    block_light_fnv1a64,
                });
            }

            chunks.push(ChunkManifest {
                chunk_x,
                chunk_z,
                data_version: c_dv,
                generation_token11,
                raw_pointer_word64,
                pointer_secded_syndrome8,
                sections,
            });
        }

        let mut cbit_vectors = Vec::new();
        for v_part in vectors_region.split("\"vectorId\": \"").skip(1) {
            let vid_end = v_part.find('"').unwrap();
            let vector_id = v_part[..vid_end].to_string();
            let tripwire_rule = extract_str_field(v_part, "tripwireRule").to_string();
            let mutated_byte_offset = extract_num_field(v_part, "mutatedByteOffset") as usize;
            let original_byte = extract_num_field(v_part, "originalByte") as u8;
            let mutated_byte = extract_num_field(v_part, "mutatedByte") as u8;
            let expect_self_healed = extract_bool_field(v_part, "expectSelfHealed");
            let expect_rejected = extract_bool_field(v_part, "expectRejected");
            cbit_vectors.push(CbitVectorManifest {
                vector_id,
                tripwire_rule,
                mutated_byte_offset,
                original_byte,
                mutated_byte,
                expect_self_healed,
                expect_rejected,
            });
        }

        bundles.push(BundleManifest {
            fixture_id,
            data_version,
            mode_a_byte_length,
            mode_b_byte_length,
            chunks,
            cbit_vectors,
        });
    }
    bundles
}

fn fixture_dir() -> PathBuf {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    manifest_dir.join("target").join("lca-golden-fixtures")
}

#[test]
fn verify_all_golden_bundles_mode_a_and_mode_b_fnv1a64_parity() {
    let dir = fixture_dir();
    let manifest_path = dir.join("lca-golden-manifest.json");
    assert!(
        manifest_path.is_file(),
        "Missing golden manifest at {:?}; run LcaGoldenFixtureTest first",
        manifest_path
    );
    let json = fs::read_to_string(&manifest_path).unwrap();
    let bundles = parse_golden_manifest(&json);
    assert_eq!(5, bundles.len(), "Expected 5 canonical golden bundles");

    let cfg = PumpkinWorldChunkConfig::lca_default();
    assert_eq!("lca", cfg.chunk_type);

    for bundle in &bundles {
        assert!(bundle.data_version > 0);
        let mode_a_bytes = fs::read(dir.join(format!("{}.modeA.lca", bundle.fixture_id))).unwrap();
        let mode_b_bytes = fs::read(dir.join(format!("{}.modeB.lca", bundle.fixture_id))).unwrap();
        assert_eq!(bundle.mode_a_byte_length, mode_a_bytes.len());
        assert_eq!(bundle.mode_b_byte_length, mode_b_bytes.len());

        // Validate Layer 1 Superblock, Layer 2 Dictionary, and Free-Slab Allocator
        let sb_a = parse_and_validate_superblock(&mode_a_bytes).unwrap();
        let mut dict_work = mode_a_bytes.clone();
        let dict_a = ChainedRegionDictionary::parse(&mut dict_work, &sb_a).unwrap();
        let alloc_a = FreeSlabAllocator::reconstruct(&mode_a_bytes, &sb_a, &dict_a.segments);
        assert!(alloc_a.active_end_slab >= alloc_a.slab_pool_base_slab);

        for (mode_label, lca_bytes) in [("Mode A", &mode_a_bytes), ("Mode B", &mode_b_bytes)] {
            for chunk_manifest in &bundle.chunks {
                let decoded = LcaPumpkinBridge::read_chunk_direct(
                    lca_bytes,
                    chunk_manifest.chunk_x,
                    chunk_manifest.chunk_z,
                    false,
                )
                .unwrap_or_else(|e| {
                    panic!(
                        "Failed to decode {} ({}) chunk ({}, {}): {}",
                        bundle.fixture_id, mode_label, chunk_manifest.chunk_x, chunk_manifest.chunk_z, e
                    )
                })
                .expect("Chunk must be present");

                assert_eq!(chunk_manifest.chunk_x, decoded.chunk_x);
                assert_eq!(chunk_manifest.chunk_z, decoded.chunk_z);
                assert_eq!(chunk_manifest.data_version, decoded.data_version);
                assert_eq!(chunk_manifest.generation_token11, decoded.generation_token8);
                if mode_label == "Mode A" {
                    assert_eq!(chunk_manifest.raw_pointer_word64, decoded.raw_pointer_word64);
                    assert_eq!(
                        chunk_manifest.pointer_secded_syndrome8,
                        decoded.pointer_secded_syndrome8
                    );
                }

                assert_eq!(
                    chunk_manifest.sections.len(),
                    decoded.sub_chunks.len(),
                    "Section count mismatch in {} ({})",
                    bundle.fixture_id,
                    mode_label
                );

                for (sec_m, sub) in chunk_manifest.sections.iter().zip(decoded.sub_chunks.iter()) {
                    assert_eq!(sec_m.section_y, sub.section_y);
                    assert_eq!(sec_m.inline_uniform, sub.inline_uniform);
                    assert_eq!(
                        sec_m.octree_occupancy_mask64,
                        sub.octree_occupancy_mask64,
                        "octreeOccupancyMask64 mismatch at Y={} in {} ({})",
                        sec_m.section_y,
                        bundle.fixture_id,
                        mode_label
                    );
                    assert_eq!(
                        sec_m.voxel_fnv1a64,
                        sub.compute_voxel_fnv1a64(),
                        "Voxel FNV-1a64 mismatch at Y={} in {} ({})",
                        sec_m.section_y,
                        bundle.fixture_id,
                        mode_label
                    );
                    assert_eq!(
                        sec_m.biome_fnv1a64,
                        sub.compute_biome_fnv1a64(),
                        "Biome FNV-1a64 mismatch at Y={} in {} ({})",
                        sec_m.section_y,
                        bundle.fixture_id,
                        mode_label
                    );
                    assert_eq!(
                        sec_m.sky_light_fnv1a64,
                        sub.compute_sky_light_fnv1a64(),
                        "SkyLight FNV-1a64 mismatch at Y={} in {} ({})",
                        sec_m.section_y,
                        bundle.fixture_id,
                        mode_label
                    );
                    assert_eq!(
                        sec_m.block_light_fnv1a64,
                        sub.compute_block_light_fnv1a64(),
                        "BlockLight FNV-1a64 mismatch at Y={} in {} ({})",
                        sec_m.section_y,
                        bundle.fixture_id,
                        mode_label
                    );
                }
            }
        }
    }
}

#[test]
fn verify_all_cbit_tripwires_and_4_tier_secded_self_healing_vectors() {
    let dir = fixture_dir();
    let json = fs::read_to_string(dir.join("lca-golden-manifest.json")).unwrap();
    let bundles = parse_golden_manifest(&json);

    for bundle in &bundles {
        let mode_a_bytes = fs::read(dir.join(format!("{}.modeA.lca", bundle.fixture_id))).unwrap();
        let mode_b_bytes = fs::read(dir.join(format!("{}.modeB.lca", bundle.fixture_id))).unwrap();
        let first_chunk = &bundle.chunks[0];
        assert_eq!(16, bundle.cbit_vectors.len());

        for vec_entry in &bundle.cbit_vectors {
            let use_mode_b = vec_entry.vector_id.contains("MODE_B");
            let mut corrupted = if use_mode_b {
                mode_b_bytes.clone()
            } else {
                mode_a_bytes.clone()
            };
            assert_eq!(
                vec_entry.original_byte,
                corrupted[vec_entry.mutated_byte_offset],
                "Original byte mismatch for {} in {}",
                vec_entry.vector_id,
                bundle.fixture_id
            );
            corrupted[vec_entry.mutated_byte_offset] = vec_entry.mutated_byte;

            if vec_entry.expect_rejected {
                let res = LcaPumpkinBridge::read_chunk_strict(
                    &corrupted,
                    first_chunk.chunk_x,
                    first_chunk.chunk_z,
                );
                assert!(
                    res.is_err(),
                    "Expected CBIT vector {} ({}) to be rejected in {}",
                    vec_entry.vector_id,
                    vec_entry.tripwire_rule,
                    bundle.fixture_id
                );
            } else if vec_entry.expect_self_healed {
                let report = heal_region_bit_rot(&mut corrupted).unwrap_or_else(|e| {
                    panic!(
                        "SEC-DED heal failed on {} in {}: {}",
                        vec_entry.vector_id, bundle.fixture_id, e
                    )
                });
                assert!(
                    report.total_healed() >= 1,
                    "Expected at least 1 healed item for {} in {}",
                    vec_entry.vector_id,
                    bundle.fixture_id
                );
                let healed_chunk = LcaPumpkinBridge::read_chunk_strict(
                    &corrupted,
                    first_chunk.chunk_x,
                    first_chunk.chunk_z,
                )
                .unwrap()
                .expect("Healed chunk must be readable");
                assert_eq!(first_chunk.sections.len(), healed_chunk.sub_chunks.len());
                for (sec_m, sub) in first_chunk
                    .sections
                    .iter()
                    .zip(healed_chunk.sub_chunks.iter())
                {
                    assert_eq!(sec_m.voxel_fnv1a64, sub.compute_voxel_fnv1a64());
                    assert_eq!(sec_m.biome_fnv1a64, sub.compute_biome_fnv1a64());
                }
            }
        }
    }
}

#[test]
fn verify_sub_chunk_y_window_probe_and_cow_save_roundtrip() {
    let dir = fixture_dir();
    let mut mode_a_bytes = fs::read(dir.join("real_1_21_R1.modeA.lca")).unwrap();
    let json = fs::read_to_string(dir.join("lca-golden-manifest.json")).unwrap();
    let bundles = parse_golden_manifest(&json);
    let b121 = bundles
        .iter()
        .find(|b| b.fixture_id == "real_1_21_R1")
        .unwrap();
    let c0 = &b121.chunks[0];

    // Axis 4: Sub-Chunk Y-window probe (minY=48, maxY=112 -> sectionY 3..=7)
    let probed =
        LcaPumpkinBridge::read_terrain_probe(&mode_a_bytes, c0.chunk_x, c0.chunk_z, 48, 112)
            .unwrap();
    assert!(!probed.is_empty());
    for sub in &probed {
        assert!((3..=7).contains(&sub.section_y));
    }

    // Axis 5: O(1) 256B CoW Slab Save
    let orig_ptr = ChunkPointer64::unpack(c0.raw_pointer_word64);
    let new_ptr =
        LcaPumpkinBridge::save_chunk_cow(&mut mode_a_bytes, c0.chunk_x, c0.chunk_z).unwrap();
    assert_eq!(orig_ptr.generation8.wrapping_add(1), new_ptr.generation8);
    assert!(new_ptr.slab_offset35 > orig_ptr.slab_offset35);

    let reloaded =
        LcaPumpkinBridge::read_chunk_strict(&mode_a_bytes, c0.chunk_x, c0.chunk_z)
            .unwrap()
            .unwrap();
    assert_eq!(new_ptr.generation8, reloaded.generation_token8);
    assert_eq!(
        c0.sections[0].voxel_fnv1a64,
        reloaded.sub_chunks[0].compute_voxel_fnv1a64()
    );

    // Full encode_and_save_chunk_cow into a fresh create_empty_region archive
    let mut fresh_region = Vec::new();
    let fresh_ptr =
        LcaPumpkinBridge::encode_and_save_chunk_cow(&mut fresh_region, &reloaded).unwrap();
    assert!(fresh_ptr.is_present());
    let decoded_fresh =
        LcaPumpkinBridge::read_chunk_strict(&fresh_region, c0.chunk_x, c0.chunk_z)
            .unwrap()
            .unwrap();
    assert_eq!(c0.sections.len(), decoded_fresh.sub_chunks.len());
    for (sec_m, sub) in c0.sections.iter().zip(decoded_fresh.sub_chunks.iter()) {
        assert_eq!(sec_m.voxel_fnv1a64, sub.compute_voxel_fnv1a64());
        assert_eq!(sec_m.biome_fnv1a64, sub.compute_biome_fnv1a64());
    }
}
