//! GPU pipelines, parked layer caches, brush source slots, and texture readback buffers.

use super::*;

/// What `textures` take on the GPU, bytes: every level and layer of each, as its format packs
/// them (before the driver's own rounding up).
pub fn textures_bytes(textures: &[wgpu::Texture]) -> u64 {
    textures
        .iter()
        .map(|t| {
            let (bw, bh) = t.format().block_dimensions();
            let block = u64::from(t.format().block_copy_size(None).unwrap_or(4));
            let layers = u64::from(t.depth_or_array_layers()) * u64::from(t.sample_count());
            (0..t.mip_level_count())
                .map(|level| {
                    let (w, h) = ((t.width() >> level).max(1), (t.height() >> level).max(1));
                    u64::from(w.div_ceil(bw)) * u64::from(h.div_ceil(bh)) * block * layers
                })
                .sum::<u64>()
        })
        .sum()
}

impl Pipelines {
    pub(crate) fn new(device: &wgpu::Device, abilities: Abilities, cache: Option<wgpu::PipelineCache>) -> Pipelines {
        let kept = cache.as_ref();
        let tex_entry = |binding| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        };
        let array_entry = |binding| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT.union(wgpu::ShaderStages::COMPUTE),
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: true },
                view_dimension: wgpu::TextureViewDimension::D2Array,
                multisampled: false,
            },
            count: None,
        };
        let sampler_entry = |binding| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT.union(wgpu::ShaderStages::COMPUTE),
            ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
            count: None,
        };
        let quad_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("layer, stroke, clip, backdrop, grains"),
            entries: &[
                tex_entry(0),
                tex_entry(1),
                tex_entry(2),
                tex_entry(3),
                array_entry(4),
                sampler_entry(5),
                tex_entry(6),
                tex_entry(7),
                tex_entry(8),
                // the paper's tile, filtered (with the grains' sampler)
                wgpu::BindGroupLayoutEntry {
                    binding: 9,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
            ],
        });
        let brush_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("shapes, grains and the selection"),
            entries: &[
                array_entry(0),
                array_entry(1),
                sampler_entry(2),
                sampler_entry(3),
                // the selection, filtered where the stamp samples it (and where a smudge moves paint)
                wgpu::BindGroupLayoutEntry {
                    binding: 4,
                    visibility: wgpu::ShaderStages::FRAGMENT.union(wgpu::ShaderStages::COMPUTE),
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
            ],
        });
        let sampled_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("sampled"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
        });
        let uniform_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("uniforms"),
            entries: &[wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: true,
                    min_binding_size: NonZeroU64::new(UNIFORM_SIZE),
                },
                count: None,
            }],
        });
        let module = |label, src: &str| {
            device.create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some(label),
                source: wgpu::ShaderSource::Wgsl(src.into()),
            })
        };
        let stamp_module =
            module("stamp", concat!(include_str!("shaders/blend.wgsl"), include_str!("shaders/stamp.wgsl")));
        let composite_module =
            module("composite", concat!(include_str!("shaders/blend.wgsl"), include_str!("shaders/composite.wgsl")));
        let display_module = module("display", include_str!("shaders/display.wgsl"));
        let surround_module = module("surround", include_str!("shaders/surround.wgsl"));
        let backdrop_module = module("backdrop", include_str!("shaders/backdrop.wgsl"));
        let transform_module = module("transform", include_str!("shaders/transform.wgsl"));
        let selection_module = module("selection", include_str!("shaders/selection.wgsl"));
        let layout = |label, groups: &[Option<&wgpu::BindGroupLayout>]| {
            device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some(label),
                bind_group_layouts: groups,
                immediate_size: 0,
            })
        };
        let stamp_layout = layout("stamp", &[Some(&uniform_layout), Some(&brush_layout)]);
        let composite_layout = layout("composite", &[Some(&quad_layout), Some(&uniform_layout)]);
        let display_pipeline_layout = layout("display", &[Some(&sampled_layout), Some(&uniform_layout)]);
        let lut_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("colour table"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D3,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
        });
        let display_lut_layout =
            layout("display with a table", &[Some(&sampled_layout), Some(&uniform_layout), Some(&lut_layout)]);
        let veil_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("veil"),
            entries: &[wgpu::BindGroupLayoutEntry {
                binding: 0,
                // (the vertices read which cells to draw)
                visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: false,
                    min_binding_size: NonZeroU64::new(size_of::<VeilUniform>() as u64),
                },
                count: None,
            }],
        });
        let veil_pipeline_layout =
            layout("veil", &[Some(&sampled_layout), Some(&uniform_layout), Some(&lut_layout), Some(&veil_layout)]);
        // a transform's copy, its sampler and the selection
        let transform_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("transform source"),
            entries: &[
                // filtered where an adjustment samples it
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                sampler_entry(1),
                tex_entry(2),
            ],
        });
        let transform_pipeline_layout = layout("transform", &[Some(&transform_layout), Some(&uniform_layout)]);
        let before_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("adjustment copy"),
            entries: &[tex_entry(0), tex_entry(1)],
        });
        let filter_pipeline_layout =
            layout("adjustment", &[Some(&transform_layout), Some(&uniform_layout), Some(&before_layout)]);
        let bounds_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("bounds"),
            entries: &[tex_entry(0)],
        });
        let bounds_module = module("bounds", include_str!("shaders/bounds.wgsl"));
        let changed_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("changed"),
            entries: &[tex_entry(0), tex_entry(1)],
        });
        let bounds_pipeline = |group: &wgpu::BindGroupLayout, fs| {
            let pipeline_layout = layout("bounds", &[Some(group)]);
            blended_pipeline(device, kept, &pipeline_layout, &bounds_module, fs, ("vs_full", fs), LAYER_FORMAT, None)
        };
        let (bounds_rows, bounds_columns) =
            (bounds_pipeline(&bounds_layout, "fs_rows"), bounds_pipeline(&bounds_layout, "fs_columns"));
        let (changed_rows, changed_columns) = (
            bounds_pipeline(&changed_layout, "fs_changed_rows"),
            bounds_pipeline(&changed_layout, "fs_changed_columns"),
        );
        let flip_module = module("flip", include_str!("shaders/flip.wgsl"));
        let flip_layout = layout("flip", &[Some(&bounds_layout)]);
        let flip_pipeline =
            |fs| blended_pipeline(device, kept, &flip_layout, &flip_module, fs, ("vs_full", fs), LAYER_FORMAT, None);
        let (flip_across, flip_down) = (flip_pipeline("fs_across"), flip_pipeline("fs_down"));
        let invert = flip_pipeline("fs_invert");
        let crop_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("crop"),
            entries: &[
                tex_entry(0),
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
            ],
        });
        let crop_module = module("crop", include_str!("shaders/crop.wgsl"));
        let crop = blended_pipeline(
            device,
            kept,
            &layout("crop", &[Some(&crop_layout)]),
            &crop_module,
            "crop",
            ("vs_full", "fs_crop"),
            LAYER_FORMAT,
            None,
        );
        let filter_module = module("filter", include_str!("shaders/filter.wgsl"));
        let filters = FILTER_PASSES
            .iter()
            .map(|&fs| {
                let pipeline = blended_pipeline(
                    device,
                    kept,
                    &filter_pipeline_layout,
                    &filter_module,
                    fs,
                    ("vs_filter", fs),
                    LAYER_FORMAT,
                    None,
                );
                (fs, pipeline)
            })
            .collect();
        let liquify = abilities.liquify_format.map(|format| {
            let liquify_module = module("liquify", include_str!("shaders/liquify.wgsl"));
            let stamp = blended_pipeline(
                device,
                kept,
                &layout("liquify stamps", &[Some(&uniform_layout)]),
                &liquify_module,
                "liquify stamps",
                ("vs_stamp", "fs_stamp"),
                LIQUIFY_SUMS_FORMAT,
                Some(ADD),
            );
            let liquify_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("liquify step"),
                entries: &[tex_entry(0), tex_entry(1), tex_entry(2)],
            });
            let step = blended_pipeline(
                device,
                kept,
                &layout("liquify step", &[Some(&uniform_layout), Some(&liquify_layout)]),
                &liquify_module,
                "liquify step",
                ("vs_step", "fs_step"),
                format,
                None,
            );
            LiquifyPipelines { stamp, step, format, layout: liquify_layout }
        });
        let surround_pipeline_layout = layout("surround", &[Some(&uniform_layout)]);
        // the compute passes, where the GPU runs them (none where it has no storage buffers)
        let compute = abilities.computes.then(|| {
            let storage = |binding| wgpu::BindGroupLayoutEntry {
                binding,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Storage { read_only: false },
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            };
            let compute_texture = |binding| wgpu::BindGroupLayoutEntry {
                binding,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Texture {
                    sample_type: wgpu::TextureSampleType::Float { filterable: false },
                    view_dimension: wgpu::TextureViewDimension::D2,
                    multisampled: false,
                },
                count: None,
            };
            let smudge_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("smudge"),
                entries: &[
                    wgpu::BindGroupLayoutEntry {
                        binding: 0,
                        visibility: wgpu::ShaderStages::COMPUTE,
                        ty: wgpu::BindingType::Buffer {
                            ty: wgpu::BufferBindingType::Uniform,
                            has_dynamic_offset: true,
                            min_binding_size: NonZeroU64::new(SMUDGE_UNIFORM_SIZE),
                        },
                        count: None,
                    },
                    storage(1),
                    storage(2),
                    storage(3),
                    storage(4),
                    // a pass of wet paint's stamps
                    wgpu::BindGroupLayoutEntry {
                        binding: 5,
                        visibility: wgpu::ShaderStages::COMPUTE,
                        ty: wgpu::BindingType::Buffer {
                            ty: wgpu::BufferBindingType::Uniform,
                            has_dynamic_offset: true,
                            min_binding_size: NonZeroU64::new(WET_PASS_SIZE),
                        },
                        count: None,
                    },
                    // a dual brush's second, and the layer as it was (its paint laid in place)
                    compute_texture(6),
                    compute_texture(7),
                    // the layer as a smudge's last pass left it
                    compute_texture(8),
                    // the image as last shown (a smudge picking up from every layer)
                    compute_texture(9),
                ],
            });
            let smudge_module = module(
                "smudge",
                concat!(
                    include_str!("shaders/blend.wgsl"),
                    include_str!("shaders/smudge.wgsl"),
                    include_str!("shaders/smudge_compute.wgsl")
                ),
            );
            let smudge_pipeline_layout = layout("smudge", &[Some(&smudge_layout), Some(&brush_layout)]);
            let smudge_pass = |label, entry| {
                device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some(label),
                    layout: Some(&smudge_pipeline_layout),
                    module: &smudge_module,
                    entry_point: Some(entry),
                    compilation_options: Default::default(),
                    cache: kept,
                })
            };
            let smudge_carry = smudge_pass("smudge carry", "cs_carry");
            let smudge_dull = smudge_pass("smudge dull", "cs_dull");
            let smudge_blur_down = smudge_pass("smudge blur down", "cs_blur_down");
            let smudge_blur_across = smudge_pass("smudge blur across", "cs_blur_across");
            let smudge_deposit = smudge_pass("smudge deposit", "cs_deposit");
            let smudge_deposit_run = smudge_pass("smudge deposit run", "cs_deposit_run");
            let smudge_synthesize = smudge_pass("smudge synthesize", "cs_synthesize");
            let smudge_wet_plots = smudge_pass("smudge wet plots", "cs_wet_plots");
            // stamps laid by their stroke's rule: the stamp's uniform, the frame's stamps, the part's
            // tiles, the stroke as it stands and where the part goes
            let read_storage = |binding| wgpu::BindGroupLayoutEntry {
                binding,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Storage { read_only: true },
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            };
            let rule_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("stamp rule"),
                entries: &[
                    wgpu::BindGroupLayoutEntry {
                        binding: 0,
                        visibility: wgpu::ShaderStages::COMPUTE,
                        ty: wgpu::BindingType::Buffer {
                            ty: wgpu::BufferBindingType::Uniform,
                            has_dynamic_offset: true,
                            min_binding_size: NonZeroU64::new(UNIFORM_SIZE),
                        },
                        count: None,
                    },
                    read_storage(1),
                    read_storage(2),
                    compute_texture(3),
                    storage(4),
                ],
            });
            let rule_module = module(
                "stamp rule",
                concat!(
                    include_str!("shaders/blend.wgsl"),
                    include_str!("shaders/stamp.wgsl"),
                    include_str!("shaders/stamp_rule.wgsl"),
                    include_str!("shaders/stamp_rule_compute.wgsl")
                ),
            );
            let stamp_rule = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("stamp rule"),
                layout: Some(&layout("stamp rule", &[Some(&rule_layout), Some(&brush_layout)])),
                module: &rule_module,
                entry_point: Some("cs_stamp_rule"),
                compilation_options: Default::default(),
                cache: kept,
            });
            ComputePipelines {
                stamp_rule,
                rule_layout,
                smudge_layout,
                smudge_carry,
                smudge_dull,
                smudge_blur_down,
                smudge_blur_across,
                smudge_deposit,
                smudge_deposit_run,
                smudge_synthesize,
                smudge_wet_plots,
            }
        });

        // in place of the compute passes or half floats where the GPU lacks either
        let fragments = (compute.is_none() || liquify.is_none()).then(|| {
            let liquify_with = liquify.is_none().then_some((&uniform_layout, &transform_layout, &filter_module));
            fragments::FragmentPipelines::new(device, kept, &brush_layout, abilities.stroke_format, liquify_with)
        });
        let overlay_module = module("overlay", include_str!("shaders/overlay.wgsl"));
        let stamp_pipeline = |label, blend| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(label),
                layout: Some(&stamp_layout),
                vertex: wgpu::VertexState {
                    module: &stamp_module,
                    entry_point: Some("vs_stamp"),
                    compilation_options: Default::default(),
                    buffers: &[Some(wgpu::VertexBufferLayout {
                        array_stride: size_of::<StampInstance>() as u64,
                        step_mode: wgpu::VertexStepMode::Instance,
                        attributes: &STAMP_ATTRIBUTES,
                    })],
                },
                primitive: wgpu::PrimitiveState::default(),
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                fragment: Some(wgpu::FragmentState {
                    module: &stamp_module,
                    entry_point: Some("fs_stamp"),
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: abilities.stroke_format,
                        blend: Some(blend),
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                multiview_mask: None,
                cache: kept,
            })
        };
        let stamp = stamp_pipeline("stamp", PREMULTIPLIED_OVER);
        let stamp_max = stamp_pipeline("stamp max", MAXIMUM);
        let full = |label, entry, blend| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(label),
                layout: Some(&composite_layout),
                vertex: wgpu::VertexState {
                    module: &composite_module,
                    entry_point: Some("vs_full"),
                    compilation_options: Default::default(),
                    buffers: &[],
                },
                primitive: wgpu::PrimitiveState::default(),
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                fragment: Some(wgpu::FragmentState {
                    module: &composite_module,
                    entry_point: Some(entry),
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: LAYER_FORMAT,
                        blend,
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                multiview_mask: None,
                cache: kept,
            })
        };
        Pipelines {
            layer: full("layer", "fs_layer", Some(PREMULTIPLIED_OVER)),
            layer_atop: full("layer atop", "fs_layer", Some(ATOP)),
            blend: full("blend", "fs_blend", None),
            solid: full("solid", "fs_solid", None),
            commit_paint: full("commit paint", "fs_commit", Some(PREMULTIPLIED_OVER)),
            commit_erase: full("commit erase", "fs_commit", Some(DESTINATION_OUT)),
            commit_locked: full("commit locked", "fs_commit_locked", None),
            transform: blended_pipeline(
                device,
                kept,
                &transform_pipeline_layout,
                &transform_module,
                "transform",
                ("vs_transform", "fs_transform"),
                LAYER_FORMAT,
                Some(PREMULTIPLIED_OVER),
            ),
            transform_static: sampled_pipeline(
                device,
                kept,
                &transform_pipeline_layout,
                &transform_module,
                "transform static",
                "vs_transform",
                "fs_static",
                LAYER_FORMAT,
            ),
            transform_layout,
            bounds_layout,
            bounds_rows,
            bounds_columns,
            changed_layout,
            changed_rows,
            changed_columns,
            flip_across,
            flip_down,
            invert,
            crop_layout,
            crop,
            before_layout,
            filters,
            filter_layout: filter_pipeline_layout,
            liquify,
            mesh: device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("warp"),
                layout: Some(&transform_pipeline_layout),
                vertex: wgpu::VertexState {
                    module: &transform_module,
                    entry_point: Some("vs_mesh"),
                    compilation_options: Default::default(),
                    buffers: &[Some(wgpu::VertexBufferLayout {
                        array_stride: size_of::<[f32; 4]>() as u64,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &MESH_ATTRIBUTES,
                    })],
                },
                primitive: wgpu::PrimitiveState::default(),
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                fragment: Some(wgpu::FragmentState {
                    module: &transform_module,
                    entry_point: Some("fs_mesh"),
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: LAYER_FORMAT,
                        blend: Some(PREMULTIPLIED_OVER),
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                multiview_mask: None,
                cache: kept,
            }),
            thumb: sampled_pipeline(
                device,
                kept,
                &display_pipeline_layout,
                &display_module,
                "thumbnail",
                "vs_thumb",
                "fs_thumb",
                LAYER_FORMAT,
            ),
            stamp,
            stamp_max,
            stroke_format: abilities.stroke_format,
            fragments,
            compute,
            lut_layout,
            display_lut_layout,
            quad_layout,
            brush_layout,
            sampled_layout,
            uniform_layout,
            display_module,
            display_pipeline_layout,

            surround_module,
            surround_pipeline_layout,

            stamp_layout,
            overlay_module,

            selection_module,

            backdrop_module,
            veil_layout,
            veil_pipeline_layout,
            cache,
            store: OnceLock::new(),
        }
    }
}

/// The adjustment passes, by their fragment entry in `filter.wgsl`.
/// A copy of `rect` of the layer-format texture `source`, for an undo step.
pub(crate) fn region_copy(
    device: &wgpu::Device,
    encoder: &mut wgpu::CommandEncoder,
    source: &wgpu::Texture,
    rect: IRect,
) -> wgpu::Texture {
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("undo"),
        size: extent(rect.width(), rect.height()),
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: LAYER_FORMAT,
        usage: wgpu::TextureUsages::COPY_SRC | wgpu::TextureUsages::COPY_DST,
        view_formats: &[],
    });
    encoder.copy_texture_to_texture(
        copy_at(source, rect.x0, rect.y0),
        copy_at(&texture, 0, 0),
        extent(rect.width(), rect.height()),
    );
    texture
}

/// The part of liquify's field (`width` by `height`, `scale` field pixels a canvas pixel) a step of
/// `stamps` reaches, field pixels from the top left to the bottom right: all of it for none.
pub(crate) fn liquify_reach(stamps: &[LiquifyStamp], (width, height): (u32, u32), [across, down]: [f32; 2]) -> [u32; 4] {
    if stamps.is_empty() {
        return [0, 0, width, height];
    }
    let (mut left, mut top, mut right, mut bottom) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for st in stamps {
        let ([x, y], r) = (st.centre, st.radius.max(LIQUIFY_LEAST_RADIUS));
        left = left.min(x - r);
        top = top.min(y - r);
        right = right.max(x + r);
        bottom = bottom.max(y + r);
    }
    let low = |v: f32, most: u32| (v.floor() as i64 - 1).clamp(0, i64::from(most)) as u32;
    let high = |v: f32, most: u32| (v.ceil() as i64 + 1).clamp(0, i64::from(most)) as u32;
    [low(left * across, width), low(top * down, height), high(right * across, width), high(bottom * down, height)]
}

/// The smallest a liquify stamp can be, canvas pixels.
pub(crate) const LIQUIFY_LEAST_RADIUS: f32 = 0.25;

/// What a liquify step's stamps add up to: an offset, and how much is rebuilt.
pub(crate) const LIQUIFY_SUMS_FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba16Float;

/// The liquify field: two floats of offset a pixel, as many bits as can be drawn into here. Each
/// step redraws the field from itself, so half floats would drift.
pub(crate) fn liquify_format(adapter: &wgpu::Adapter) -> wgpu::TextureFormat {
    let wanted = wgpu::TextureFormat::Rg32Float;
    let needed = wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::RENDER_ATTACHMENT;
    let can = adapter.get_texture_format_features(wanted).allowed_usages.contains(needed);
    if can { wanted } else { wgpu::TextureFormat::Rg16Float }
}

/// Added to what is there (liquify stamps into the field).
pub(crate) const ADD: wgpu::BlendState = wgpu::BlendState {
    color: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::One,
        dst_factor: wgpu::BlendFactor::One,
        operation: wgpu::BlendOperation::Add,
    },
    alpha: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::One,
        dst_factor: wgpu::BlendFactor::One,
        operation: wgpu::BlendOperation::Add,
    },
};

pub(crate) const FILTER_PASSES: [&str; 17] = [
    "fs_glitch",
    "fs_clone",
    "fs_liquified",
    "fs_hsb",
    "fs_down",
    "fs_blur",
    "fs_up",
    "fs_sharpen",
    "fs_noise",
    "fs_streak",
    "fs_zoom",
    "fs_chroma",
    "fs_halftone",
    "fs_bloom",
    "fs_curves",
    "fs_gradient",
    "fs_balance",
];

/// A full-screen pipeline (no vertex buffers) with its blending.
pub(crate) fn blended_pipeline(
    device: &wgpu::Device,
    cache: Option<&wgpu::PipelineCache>,
    layout: &wgpu::PipelineLayout,
    module: &wgpu::ShaderModule,
    label: &str,
    (vs, fs): (&str, &str),
    format: wgpu::TextureFormat,
    blend: Option<wgpu::BlendState>,
) -> wgpu::RenderPipeline {
    device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some(label),
        layout: Some(layout),
        vertex: wgpu::VertexState {
            module,
            entry_point: Some(vs),
            compilation_options: Default::default(),
            buffers: &[],
        },
        primitive: wgpu::PrimitiveState::default(),
        depth_stencil: None,
        multisample: wgpu::MultisampleState::default(),
        fragment: Some(wgpu::FragmentState {
            module,
            entry_point: Some(fs),
            compilation_options: Default::default(),
            targets: &[Some(wgpu::ColorTargetState { format, blend, write_mask: wgpu::ColorWrites::ALL })],
        }),
        multiview_mask: None,
        cache,
    })
}

pub(crate) fn sampled_pipeline(
    device: &wgpu::Device,
    cache: Option<&wgpu::PipelineCache>,
    layout: &wgpu::PipelineLayout,
    module: &wgpu::ShaderModule,
    label: &str,
    vs: &str,
    fs: &str,
    format: wgpu::TextureFormat,
) -> wgpu::RenderPipeline {
    blended_pipeline(device, cache, layout, module, label, (vs, fs), format, None)
}

/// One frame's uniforms, a slot per draw.
#[derive(Default)]
pub(crate) struct UniformData {
    pub(crate) data: Vec<u8>,
}

impl UniformData {
    /// Adds `value` in the next slot (as many as it fills) and returns its dynamic offset.
    pub(crate) fn push<T: bytemuck::Pod>(&mut self, value: &T) -> u32 {
        let offset = self.data.len();
        self.data.extend_from_slice(bytemuck::bytes_of(value));
        let slots = (size_of::<T>() as u64).div_ceil(SLOT).max(1);
        self.data.resize(offset + (slots * SLOT) as usize, 0);
        u32::try_from(offset).unwrap_or(0)
    }
}

/// `ops` without the tips taken off again among them: what a tip covered comes back as it was, so
/// one that never reached the screen is work for nothing. Behind with a fast stroke, a frame has
/// a tip for every input event since the last, and drawing them all left it further behind.
pub(crate) fn without_passing_tips(ops: Vec<Op>) -> Vec<Op> {
    let mut kept: Vec<Op> = Vec::with_capacity(ops.len());
    for op in ops {
        if let Op::Untip(rect) = op
            && let [.., Op::SaveTip(saved), Op::Stamps { .. }] = kept.as_slice()
            && *saved == rect
        {
            kept.truncate(kept.len() - 2);
            continue;
        }
        kept.push(op);
    }
    kept
}

/// Whether `ops` lay a stroke again from scratch (a layer's, not an adjustment's mask) and then end
/// it: its end redrawn whole.
pub(crate) fn lays_ended_stroke_again(ops: &[Op]) -> bool {
    let from = ops.iter().position(|op| matches!(op, Op::Stamps { clear: true, pencil: false, .. }));
    from.is_some_and(|from| ops.iter().skip(from).any(|op| matches!(op, Op::Commit(_))))
}

/// `stamps` laid after those `laid` holds, as far as it keeps them (see [`LAID_KEPT_MOST`]).
pub(crate) fn keep_laid(laid: &mut Option<Vec<Stamp>>, stamps: &[Stamp]) {
    if let Some(kept) = laid.as_mut() {
        if kept.len() + stamps.len() <= LAID_KEPT_MOST {
            kept.extend_from_slice(stamps);
        } else {
            *laid = None;
        }
    }
}

/// How many of `stamps` (a stroke's final form) were laid already: those `laid` holds, if they are
/// all still at its start (and laying it whole isn't asked for, `whole`).
pub(crate) fn laid_before(laid: Option<&[Stamp]>, stamps: &[Stamp], whole: bool) -> Option<usize> {
    laid.filter(|laid| !whole && !laid.is_empty() && stamps.starts_with(laid)).map(<[Stamp]>::len)
}

/// `ops` without the open stroke's stamps into `layer` at their end (not its second's, nor an
/// adjustment's mask's), nor the tips put aside and given back among them, back to the last other
/// work: the stroke redrawn from scratch after them clears what they would lay.
pub(crate) fn drop_superseded_stamps(ops: &mut Vec<Op>, layer: LayerId) {
    while let Some(op) = ops.last() {
        let superseded = match op {
            Op::Stamps { second: false, pencil: false, layer: on, .. } => *on == layer,
            Op::SaveTip(_) | Op::Untip(_) => true,
            _ => false,
        };
        if !superseded {
            break;
        }
        ops.pop();
    }
}

/// The work of `stamps` on a canvas `full` (see [`stamp_work`]).
pub(crate) fn stamps_work(stamps: &[StampInstance], full: IRect, in_place: bool) -> u64 {
    stamps.iter().fold(0, |work, s| work.saturating_add(stamp_work(s, full, in_place)))
}

/// `ops` with stamps of more work than `most`, or more than `most_stamps` of them, as several in a
/// row of no more (a stamp is never cut in two): a stroke redrawn whole would otherwise reach the
/// GPU as one piece.
pub(crate) fn in_pieces(ops: Vec<Op>, full: IRect, most: u64, most_stamps: u64) -> Vec<Op> {
    let mut pieces = Vec::with_capacity(ops.len());
    for op in ops {
        let Op::Stamps { second, instances, wet, picks, times, style, clear, layer, pencil } = op else {
            pieces.push(op);
            continue;
        };
        let in_place = style.in_place() && !second;
        // how many stamps go in each
        let mut counts = Vec::new();
        let (mut count, mut work) = (0usize, 0u64);
        for s in &instances {
            let more = stamp_work(s, full, in_place);
            if count > 0 && (work.saturating_add(more) > most || count as u64 >= most_stamps) {
                counts.push(count);
                (count, work) = (0, 0);
            }
            count += 1;
            work = work.saturating_add(more);
        }
        if counts.is_empty() {
            pieces.push(Op::Stamps { second, instances, wet, picks, times, style, clear, layer, pencil });
            continue;
        }
        counts.push(count);
        let (mut instances, mut wet, mut times) = (instances.into_iter(), wet.into_iter(), times.into_iter());
        let mut picks = picks.into_iter();
        for (i, count) in counts.into_iter().enumerate() {
            pieces.push(Op::Stamps {
                second,
                instances: instances.by_ref().take(count).collect(),
                wet: wet.by_ref().take(count).collect(),
                picks: picks.by_ref().take(count).collect(),
                times: times.by_ref().take(count).collect(),
                style,
                // from scratch the first time only
                clear: clear && i == 0,
                layer,
                pencil,
            });
        }
    }
    pieces
}

/// The rows of `rect` (on the picture) out of a picture `width` pixels wide (RGBA8).
pub(crate) fn cropped(pixels: &[u8], width: u32, rect: IRect) -> Vec<u8> {
    let (row, x0, x1) = (width as usize * 4, rect.x0.max(0) as usize * 4, rect.x1.max(0) as usize * 4);
    let mut out = Vec::with_capacity(rect.width() as usize * rect.height() as usize * 4);
    for line in pixels.chunks_exact(row.max(1)).skip(rect.y0.max(0) as usize).take(rect.height() as usize) {
        out.extend_from_slice(line.get(x0..x1).unwrap_or_default());
    }
    out
}

/// A buffer holding `contents`, made as wgpu's `create_buffer_init` makes one but never panicking:
/// where the device can't make it (past its limits or its memory, or the device lost: that one
/// panicked then), it comes back holding nothing, said in the log, and what uses it fails as the
/// GPU's errors do (said, not fatal).
pub(crate) fn buffer_init(
    device: &wgpu::Device,
    label: &str,
    contents: &[u8],
    usage: wgpu::BufferUsages,
) -> wgpu::Buffer {
    let size = (contents.len() as u64).next_multiple_of(wgpu::COPY_BUFFER_ALIGNMENT).max(wgpu::COPY_BUFFER_ALIGNMENT);
    #[cfg(test)]
    let size = if tests::fails(label) { device.limits().max_buffer_size.saturating_add(4) } else { size };
    let buffer =
        device.create_buffer(&wgpu::BufferDescriptor { label: Some(label), size, usage, mapped_at_creation: true });
    match buffer.get_mapped_range_mut(..) {
        // (its size is at least as many bytes)
        Ok(mut view) => view.slice(..contents.len()).copy_from_slice(contents),
        Err(e) => log::error!("the buffer {label} couldn't be made: {e}"),
    }
    buffer.unmap();
    buffer
}

pub(crate) fn texture(device: &wgpu::Device, label: &str, width: u32, height: u32, format: wgpu::TextureFormat) -> wgpu::Texture {
    // where the GPU's block compression loses parts of a picture redrawn a part at a time (Mali),
    // a texture that can be a storage one is kept uncompressed
    let uncompressed = crate::textures_uncompressed() && format == wgpu::TextureFormat::Rgba8Unorm;
    #[cfg(test)]
    let width = if tests::fails(label) { device.limits().max_texture_dimension_2d + 1 } else { width };
    device.create_texture(&wgpu::TextureDescriptor {
        label: Some(label),
        size: extent(width, height),
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format,
        usage: wgpu::TextureUsages::TEXTURE_BINDING
            | wgpu::TextureUsages::RENDER_ATTACHMENT
            | wgpu::TextureUsages::COPY_SRC
            | wgpu::TextureUsages::COPY_DST
            | if uncompressed { wgpu::TextureUsages::STORAGE_BINDING } else { wgpu::TextureUsages::empty() },
        view_formats: &[],
    })
}

/// A pair of textures layers are composited onto, the result in one of them.
/// The smallest box [x0, y0, x1, y1) around a thumbnail's pixels with any alpha.
pub(crate) fn alpha_bounds(t: &Thumbnail) -> Option<[u32; 4]> {
    painted_bounds(t, |[_, _, _, alpha]| alpha > 0)
}

/// Where a mask's thumbnail is painted on: what isn't white.
pub(crate) fn ink_bounds(t: &Thumbnail) -> Option<[u32; 4]> {
    painted_bounds(t, |[r, ..]| r < 255)
}

/// The pixels of a thumbnail that `painted` holds for, as [x0, y0, x1, y1); none if none.
pub(crate) fn painted_bounds(t: &Thumbnail, painted: impl Fn([u8; 4]) -> bool) -> Option<[u32; 4]> {
    painted_bounds_of(&t.rgba, t.width, painted)
}

/// [`painted_bounds`] of pixels `width` wide, a row at a time: a row's first and last painted
/// pixels only widen the box.
pub(crate) fn painted_bounds_of(rgba: &[u8], width: u32, painted: impl Fn([u8; 4]) -> bool) -> Option<[u32; 4]> {
    let mut b: Option<[u32; 4]> = None;
    for (y, row) in rgba.chunks_exact(width.max(1) as usize * 4).enumerate() {
        let pixels = row.as_chunks::<4>().0;
        let Some(first) = pixels.iter().position(|p| painted(*p)) else { continue };
        let last = pixels.iter().rposition(|p| painted(*p)).unwrap_or(first);
        let (x0, x1, y) = (first as u32, last as u32 + 1, y as u32);
        b = Some(match b {
            Some([bx0, by0, bx1, _]) => [bx0.min(x0), by0, bx1.max(x1), y + 1],
            None => [x0, y, x1, y + 1],
        });
    }
    b
}

/// The old [`painted_bounds`], a pixel at a time: what [`painted_bounds_of`] must still give.
#[cfg(test)]
pub(crate) fn painted_bounds_slowly(t: &Thumbnail, painted: impl Fn([u8; 4]) -> bool) -> Option<[u32; 4]> {
    let mut b: Option<[u32; 4]> = None;
    for (i, px) in t.rgba.as_chunks::<4>().0.iter().enumerate() {
        if painted(*px) {
            let (x, y) = ((i as u32) % t.width.max(1), (i as u32) / t.width.max(1));
            b = Some(match b {
                Some([x0, y0, x1, y1]) => [x0.min(x), y0.min(y), x1.max(x + 1), y1.max(y + 1)],
                None => [x, y, x + 1, y + 1],
            });
        }
    }
    b
}

/// The longest side of a parked layer's small pictures, pixels: as big as any thumbnail asked for.
pub(crate) const PARKED_PICTURE_SIDE: u32 = 192;

/// How a remake of the caches went: over how many renders, and the most items one part took.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RemakeStats {
    pub renders: u32,
    pub largest_part: usize,
}

/// A layer's pixels made ready to park: compressed, with its small pictures. Made on any thread
/// (the slow part of [`Canvas::park_pixels`]), then given to [`Canvas::park_ready`].
pub struct ReadyToPark {
    pub(crate) parked: Parked,
    pub(crate) pictures: Pictures,
}

impl ReadyToPark {
    /// Of a layer's pixels (premultiplied RGBA, `width` x `height`).
    pub fn of(rgba: &[u8], width: u32, height: u32) -> ReadyToPark {
        let pictures = Pictures::of(rgba, width, height);
        ReadyToPark { parked: Parked::of(rgba, width, pictures.bounds), pictures }
    }

    /// How many bytes the compressed pixels take.
    pub fn bytes(&self) -> usize {
        self.parked.lz4.len()
    }
}

/// A parked layer's pixels: the rectangle painted (none if nothing), its rows compressed, so a
/// layer drawn on in one place costs little to keep and to bring back.
pub(crate) struct Parked {
    pub(crate) rect: Option<IRect>,
    pub(crate) lz4: Vec<u8>,
}

impl Parked {
    /// Of a layer's pixels (premultiplied RGBA, `width` wide), painted only within `rect`.
    pub(crate) fn of(rgba: &[u8], width: u32, rect: Option<IRect>) -> Parked {
        let rect = rect.filter(|r| !r.is_empty());
        let mut rows = Vec::new();
        if let Some(r) = rect {
            let row = r.width() as usize * 4;
            rows.reserve(row * r.height() as usize);
            for y in r.y0..r.y1 {
                let start = (y as usize * width as usize + r.x0 as usize) * 4;
                rows.extend_from_slice(rgba.get(start..start + row).unwrap_or_default());
            }
        }
        Parked { rect, lz4: procraft_engine::applz4::encode(&rows) }
    }

    /// The painted rectangle's rows, one after another; none if damaged.
    pub(crate) fn rows(&self) -> Option<Vec<u8>> {
        let size = self.rect.map_or(0, |r| r.width() as usize * r.height() as usize * 4);
        procraft_engine::applz4::decode(&self.lz4, size).filter(|p| p.len() == size)
    }

    /// The whole layer (`width` x `height`, clear outside the rectangle); none if damaged.
    pub(crate) fn pixels(&self, width: u32, height: u32) -> Option<Vec<u8>> {
        let rows = self.rows()?;
        let mut full = vec![0u8; width as usize * height as usize * 4];
        if let Some(r) = self.rect {
            let row = r.width() as usize * 4;
            for (y, src) in (r.y0..r.y1).zip(rows.chunks_exact(row.max(1))) {
                let start = (y as usize * width as usize + r.x0 as usize) * 4;
                full.get_mut(start..start + row)?.copy_from_slice(src);
            }
        }
        Some(full)
    }
}

/// A parked layer's small pictures: the whole layer, and what is drawn on it (none if nothing);
/// and where it is drawn (none if nowhere).
pub(crate) struct Pictures {
    pub(crate) whole: Thumbnail,
    pub(crate) drawn: Option<Thumbnail>,
    pub(crate) bounds: Option<IRect>,
}

impl Pictures {
    /// Of a layer's pixels (premultiplied RGBA, `width` x `height`).
    pub(crate) fn of(rgba: &[u8], width: u32, height: u32) -> Pictures {
        // read where they are, the layer neither copied nor cut out first (a large one is many
        // megabytes, and an undo brings one back and parks another)
        let whole = shrink_part(rgba, width, [0, 0, width, height], PARKED_PICTURE_SIDE);
        let painted = painted_bounds_of(rgba, width, |[_, _, _, alpha]| alpha > 0);
        let drawn = painted.map(|b| shrink_part(rgba, width, b, PARKED_PICTURE_SIDE));
        let bounds =
            painted.map(|[x0, y0, x1, y1]| IRect { x0: x0 as i32, y0: y0 as i32, x1: x1 as i32, y1: y1 as i32 });
        Pictures { whole, drawn, bounds }
    }
}

/// A picture box-filtered to fit `side` pixels (never enlarged).
pub(crate) fn shrink(t: &Thumbnail, side: u32) -> Thumbnail {
    shrink_part(&t.rgba, t.width, [0, 0, t.width, t.height], side)
}

/// Part [x0, y0, x1, y1) of a picture `width` wide (its rows in `rgba`), box-filtered to fit `side`
/// pixels (never enlarged): each pixel the rounded mean of those that fall in it, as [`shrink`]
/// was before, but which column each source column goes to worked out once, the counts by row
/// and column, and clear pixels (nothing to add) passed over.
pub(crate) fn shrink_part(rgba: &[u8], width: u32, [x0, y0, x1, y1]: [u32; 4], side: u32) -> Thumbnail {
    let (pw, ph) = ((x1.saturating_sub(x0)).max(1), (y1.saturating_sub(y0)).max(1));
    let scale = (side.max(1) as f32 / pw.max(ph) as f32).min(1.0);
    let w = ((pw as f32 * scale).round() as u32).clamp(1, pw);
    let h = ((ph as f32 * scale).round() as u32).clamp(1, ph);
    let stride = width as usize * 4;
    let row_of = |y: u32| {
        let start = (y0 + y) as usize * stride + x0 as usize * 4;
        rgba.get(start..start + pw as usize * 4).unwrap_or_default()
    };
    if (w, h) == (pw, ph) {
        let mut out = Vec::with_capacity(pw as usize * ph as usize * 4);
        for y in 0..ph {
            out.extend_from_slice(row_of(y));
        }
        return Thumbnail { width: pw, height: ph, rgba: out };
    }
    let columns: Vec<usize> = (0..pw).map(|x| (u64::from(x) * u64::from(w) / u64::from(pw)) as usize).collect();
    let mut per_column = vec![0u32; w as usize];
    for &c in &columns {
        if let Some(n) = per_column.get_mut(c) {
            *n += 1;
        }
    }
    let mut per_row = vec![0u32; h as usize];
    let mut sums = vec![0u32; w as usize * h as usize * 4];
    let line = w as usize * 4;
    for y in 0..ph {
        let ty = (u64::from(y) * u64::from(h) / u64::from(ph)) as usize;
        if let Some(n) = per_row.get_mut(ty) {
            *n += 1;
        }
        let Some(out) = sums.get_mut(ty * line..(ty + 1) * line) else { continue };
        for (px, &tx) in row_of(y).as_chunks::<4>().0.iter().zip(&columns) {
            if u32::from_ne_bytes(*px) == 0 {
                continue;
            }
            if let Some(sum) = out.get_mut(tx * 4..tx * 4 + 4) {
                for (s, v) in sum.iter_mut().zip(px) {
                    *s += u32::from(*v);
                }
            }
        }
    }
    let mut out = Vec::with_capacity(w as usize * h as usize * 4);
    for (row, rn) in sums.chunks(line).zip(&per_row) {
        for (sum, cn) in row.as_chunks::<4>().0.iter().zip(&per_column) {
            let n = (rn * cn).max(1);
            out.extend(sum.iter().map(|v| ((v + n / 2) / n) as u8));
        }
    }
    Thumbnail { width: w, height: h, rgba: out }
}

/// The old [`shrink`], a pixel at a time: what [`shrink_part`] must still give.
#[cfg(test)]
pub(crate) fn shrink_slowly(t: &Thumbnail, side: u32) -> Thumbnail {
    let (width, height) = (t.width.max(1), t.height.max(1));
    let scale = (side.max(1) as f32 / width.max(height) as f32).min(1.0);
    let w = ((width as f32 * scale).round() as u32).clamp(1, width);
    let h = ((height as f32 * scale).round() as u32).clamp(1, height);
    if (w, h) == (width, height) {
        return Thumbnail { width, height, rgba: t.rgba.clone() };
    }
    let mut sums = vec![0u32; w as usize * h as usize * 4];
    let mut counts = vec![0u32; w as usize * h as usize];
    for (y, row) in t.rgba.chunks_exact(width as usize * 4).enumerate() {
        let ty = (y as u64 * u64::from(h) / u64::from(height)) as usize;
        for (x, px) in row.as_chunks::<4>().0.iter().enumerate() {
            let tx = (x as u64 * u64::from(w) / u64::from(width)) as usize;
            let i = ty * w as usize + tx;
            if let Some(c) = counts.get_mut(i) {
                *c += 1;
            }
            for (k, v) in px.iter().enumerate() {
                if let Some(sum) = sums.get_mut(i * 4 + k) {
                    *sum += u32::from(*v);
                }
            }
        }
    }
    let rgba = sums
        .as_chunks::<4>()
        .0
        .iter()
        .zip(&counts)
        .flat_map(|(sum, n)| sum.iter().map(move |v| ((v + n / 2) / (*n).max(1)) as u8).collect::<Vec<_>>())
        .collect();
    Thumbnail { width: w, height: h, rgba }
}

/// Part [x0, y0, x1, y1) of a thumbnail.
pub(crate) fn crop(t: &Thumbnail, [x0, y0, x1, y1]: [u32; 4]) -> Thumbnail {
    let row = t.width as usize * 4;
    let mut rgba = Vec::with_capacity((x1 - x0) as usize * (y1 - y0) as usize * 4);
    for y in y0..y1 {
        let start = y as usize * row + x0 as usize * 4;
        rgba.extend_from_slice(t.rgba.get(start..start + (x1 - x0) as usize * 4).unwrap_or_default());
    }
    Thumbnail { width: x1 - x0, height: y1 - y0, rgba }
}

/// A pair of textures layers are composited onto, the result in one of them: the first, and
/// the second that blend modes other than Normal draw into.
pub(crate) struct Stack<'a> {
    pub(crate) first: (&'a wgpu::Texture, &'a wgpu::TextureView),
    pub(crate) second: Second<'a>,
}

pub(crate) enum Second<'a> {
    Made(&'a wgpu::Texture, &'a wgpu::TextureView),
    /// Made the first time a blend needs it.
    OnDemand(&'a OnDemand, &'a wgpu::Device),
}

/// A texture made the first time it is wanted, and kept from then on: one most canvases never
/// want (a ping-pong partner, a scratch copy).
pub(crate) struct OnDemand {
    pub(crate) label: &'static str,
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) format: wgpu::TextureFormat,
    pub(crate) made: OnceLock<(wgpu::Texture, wgpu::TextureView)>,
}

impl OnDemand {
    pub(crate) fn new(label: &'static str, width: u32, height: u32, format: wgpu::TextureFormat) -> OnDemand {
        OnDemand { label, width, height, format, made: OnceLock::new() }
    }

    pub(crate) fn get<'a>(&'a self, device: &wgpu::Device) -> (&'a wgpu::Texture, &'a wgpu::TextureView) {
        let (texture, view) = self.made.get_or_init(|| {
            let t = texture(device, self.label, self.width, self.height, self.format);
            let v = t.create_view(&wgpu::TextureViewDescriptor::default());
            (t, v)
        });
        (texture, view)
    }

    /// The texture, if it has been made.
    pub(crate) fn made(&self) -> Option<&wgpu::Texture> {
        self.made.get().map(|(t, _)| t)
    }

    /// Lets the texture go, to be made again when next wanted.
    pub(crate) fn let_go(&mut self) {
        self.made.take();
    }
}

/// Buffers that pixels go to the GPU through, written while mapped and copied in a command
/// encoder's order, then mapped again for the next upload once the GPU is done with them:
/// `queue.write_texture` makes a buffer for every write, which for a layer the canvas's size took
/// several times the copy itself on a tablet. A few are kept (to [`UPLOADS_KEPT`] bytes).
#[derive(Default)]
pub(crate) struct Uploads {
    pub(crate) kept: Mutex<Vec<(wgpu::Buffer, Arc<AtomicU8>)>>,
}

/// An upload buffer's state: on its way back, mapped again, or not mapped (let go).
pub(crate) const UPLOAD_WAITING: u8 = 0;
pub(crate) const UPLOAD_READY: u8 = 1;
pub(crate) const UPLOAD_FAILED: u8 = 2;

/// The most bytes of upload buffers kept between uploads: two layers the size of a large tablet
/// canvas; bigger ones are made for each upload.
pub(crate) const UPLOADS_KEPT: u64 = 96 << 20;

impl Uploads {
    /// A mapped buffer of `size` bytes or more: one kept and back from the GPU, or one waited
    /// for, or else a new one.
    pub(crate) fn take(&self, device: &wgpu::Device, size: u64) -> wgpu::Buffer {
        let mut kept = self.kept.lock().unwrap_or_else(PoisonError::into_inner);
        kept.retain(|(_, state)| state.load(Ordering::Acquire) != UPLOAD_FAILED);
        let fits = |kept: &[(wgpu::Buffer, Arc<AtomicU8>)]| {
            kept.iter().position(|(b, state)| b.size() >= size && state.load(Ordering::Acquire) == UPLOAD_READY)
        };
        let _ = crate::poll(device, wgpu::PollType::Poll);
        let mut found = fits(&kept);
        if found.is_none() && kept.iter().any(|(b, _)| b.size() >= size) {
            // one on its way back: waited for rather than another as big made
            let _ = crate::poll(device, wgpu::PollType::wait_indefinitely());
            found = fits(&kept);
        }
        if let Some(i) = found {
            return kept.swap_remove(i).0;
        }
        device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("upload"),
            size,
            usage: wgpu::BufferUsages::MAP_WRITE | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: true,
        })
    }

    /// `buffer` back once what used it has been sent: mapped again, and kept while there is room.
    pub(crate) fn give_back(&self, buffer: wgpu::Buffer) {
        let mut kept = self.kept.lock().unwrap_or_else(PoisonError::into_inner);
        let room = kept.iter().map(|(b, _)| b.size()).sum::<u64>().saturating_add(buffer.size()) <= UPLOADS_KEPT;
        if !room {
            return;
        }
        let state = Arc::new(AtomicU8::new(UPLOAD_WAITING));
        let mapped = state.clone();
        buffer.slice(..).map_async(wgpu::MapMode::Write, move |r| {
            mapped.store(if r.is_ok() { UPLOAD_READY } else { UPLOAD_FAILED }, Ordering::Release);
        });
        kept.push((buffer, state));
    }
}

/// Pictures of the top level's items that aren't being worked on, so only those that are
/// composite each time (and the others' layers needn't be at hand): the background with every
/// item below them, and a run of plain items above them over transparency.
pub(crate) struct Caches {
    /// The top level's items composited live, and every layer in them.
    pub(crate) live: Vec<LayerId>,
    pub(crate) live_layers: HashSet<LayerId>,
    /// The items below them, composited over the background, and what that was made from.
    pub(crate) below: CachePicture,
    pub(crate) below_items: Vec<LayerId>,
    pub(crate) below_key: u64,
    /// The items above them, over transparency, and what from.
    pub(crate) above: CachePicture,
    pub(crate) above_items: Vec<LayerId>,
    pub(crate) above_key: u64,
}

/// One of the composite's caches (see [`Caches`]): a picture the canvas's size and the other half
/// of the ping-pong pair it is made with, an item of a blend mode at a time. On a device of little
/// memory it has none of its own: the composite's is borrowed while it is made (the two are never
/// made at once), its picture settled back into its own texture after each part.
pub(crate) struct CachePicture {
    pub(crate) texture: wgpu::Texture,
    pub(crate) view: wgpu::TextureView,
    pub(crate) second: Option<(wgpu::Texture, wgpu::TextureView)>,
}

impl CachePicture {
    pub(crate) fn new(device: &wgpu::Device, label: &str, width: u32, height: u32, paired: bool) -> CachePicture {
        let made = || {
            let t = texture(device, label, width, height, LAYER_FORMAT);
            let v = t.create_view(&wgpu::TextureViewDescriptor::default());
            (t, v)
        };
        let (texture, view) = made();
        CachePicture { texture, view, second: paired.then(made) }
    }

    /// The pair it is made with: its own second texture, or `borrowed`.
    pub(crate) fn stack<'a>(&'a self, borrowed: &'a OnDemand, device: &'a wgpu::Device) -> Stack<'a> {
        let second = match &self.second {
            Some((t, v)) => Second::Made(t, v),
            None => Second::OnDemand(borrowed, device),
        };
        Stack { first: (&self.texture, &self.view), second }
    }

    /// Whether it borrows the second texture of its pair.
    pub(crate) fn borrows(&self) -> bool {
        self.second.is_none()
    }

    pub(crate) fn textures(&self) -> impl Iterator<Item = &wgpu::Texture> {
        std::iter::once(&self.texture).chain(self.second.as_ref().map(|(t, _)| t))
    }
}

/// How many layers a render reads back to park, at most, and how long it goes on reading them
/// (after the first).
pub(crate) const MOST_PARK_READS: usize = 4;
pub(crate) const PARK_SLICE: std::time::Duration = std::time::Duration::from_millis(6);

/// The room (in layers' textures) a remake keeps free for its next part, letting go of the
/// layers it brought back only so far.
pub(crate) const LET_GO_ROOM: usize = 2 * MOST_PART_ITEMS;

/// The most items a part of a remake composites before it is sent (and the time looked at).
pub(crate) const MOST_PART_ITEMS: usize = 8;

/// The most uniform slots a filter takes: a wide blur's halvings (at most five), its two passes
/// and the filter's own.
pub(crate) const FILTER_SLOTS: usize = 8;

/// How long each render goes on remaking caches before it lets the app go on.
pub(crate) const REMAKE_SLICE: std::time::Duration = std::time::Duration::from_millis(16);

/// Caches being remade a little each render: their textures, how far each has got (the next item
/// and where its stack's result is; none when done or not remade), what they are made from,
/// and the layers worked on, which stay.
pub(crate) struct Remake {
    pub(crate) caches: Caches,
    pub(crate) below: Option<(usize, usize)>,
    pub(crate) above: Option<(usize, usize)>,
    pub(crate) below_items: Vec<LayerId>,
    pub(crate) below_key: u64,
    pub(crate) above_items: Vec<LayerId>,
    pub(crate) above_key: u64,
    pub(crate) keep: HashSet<LayerId>,
    /// For the log: when it started, over how many renders, and the layers brought back for it
    /// and the time that took.
    pub(crate) started: std::time::Instant,
    pub(crate) slices: u32,
    pub(crate) largest_part: usize,
    pub(crate) restored: usize,
    pub(crate) restoring: std::time::Duration,
}

/// How many levels of group textures compositing `doc` takes: down to the deepest group
/// composited on its own (with Animation Assist any group, its frame's onion skin tinted whole).
pub(crate) fn group_levels(doc: &Document) -> usize {
    if doc.animation.is_some() {
        return doc.max_depth();
    }
    let alone = doc.layers().iter().filter(|l| l.kind == LayerKind::Group && !l.passes_through());
    alone.map(|l| doc.depth(l.id) + 1).max().unwrap_or(0)
}

/// Whether item `id` composited over transparency, then over what is below, shows as it does
/// composited straight onto it: normal, and if a group passing through, only of such items.
pub(crate) fn plain(doc: &Document, id: LayerId) -> bool {
    match doc.layer(id) {
        Some(l) if l.kind == LayerKind::Mask => true,
        Some(l) if l.blend != BlendMode::Normal => false,
        Some(l) if l.passes_through() => doc.members(Some(id)).iter().all(|m| plain(doc, *m)),
        _ => true,
    }
}

/// What a top-level item composites from: itself and its mask, or a group and all it holds.
pub(crate) fn item_layers(doc: &Document, item: LayerId) -> Vec<LayerId> {
    let mut layers = vec![item];
    match doc.layer(item).map(|l| l.kind) {
        Some(LayerKind::Group) => layers.extend(doc.contents(item)),
        Some(LayerKind::Paint) => layers.extend(doc.mask_of(item)),
        _ => {}
    }
    layers
}

/// A fingerprint of how `items` composite (with the background, if `background`): every setting
/// of every layer in them, and which layer is shown alone. Their pixels change only through work
/// that drops the caches.
pub(crate) fn items_key(doc: &Document, items: &[LayerId], background: bool) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    if background {
        format!("{:?} {}", doc.background, doc.background_visible).hash(&mut h);
    }
    format!("{:?}", doc.solo).hash(&mut h);
    for &item in items {
        for id in item_layers(doc, item) {
            format!("{:?}", doc.layer(id)).hash(&mut h);
        }
    }
    h.finish()
}

impl<'a> Stack<'a> {
    /// Both made already.
    pub(crate) fn pair(textures: &'a [wgpu::Texture; 2], views: &'a [wgpu::TextureView; 2]) -> Stack<'a> {
        let [t0, t1] = textures;
        let [v0, v1] = views;
        Stack { first: (t0, v0), second: Second::Made(t1, v1) }
    }

    /// The second texture, made now if it has to be.
    pub(crate) fn second(&self) -> (&'a wgpu::Texture, &'a wgpu::TextureView) {
        match self.second {
            Second::Made(t, v) => (t, v),
            Second::OnDemand(lazy, device) => lazy.get(device),
        }
    }

    /// Leaves the result in the first texture (where `current` says it is, within `rect`).
    pub(crate) fn settle(&self, encoder: &mut wgpu::CommandEncoder, rect: IRect, current: usize) {
        if current == 1 {
            encoder.copy_texture_to_texture(
                copy_at(self.second().0, rect.x0, rect.y0),
                copy_at(self.first.0, rect.x0, rect.y0),
                extent(rect.width(), rect.height()),
            );
        }
    }
}

pub(crate) fn quad_bind(
    device: &wgpu::Device,
    layout: &wgpu::BindGroupLayout,
    views: [&wgpu::TextureView; 7],
    grains: &wgpu::TextureView,
    grain_sampler: &wgpu::Sampler,
    paper: &wgpu::TextureView,
) -> wgpu::BindGroup {
    let [a, b, c, d, mask, second, clip_mask] = views;
    device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("layer, stroke, clip, backdrop, grains"),
        layout,
        entries: &[
            wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(a) },
            wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(b) },
            wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(c) },
            wgpu::BindGroupEntry { binding: 3, resource: wgpu::BindingResource::TextureView(d) },
            wgpu::BindGroupEntry { binding: 4, resource: wgpu::BindingResource::TextureView(grains) },
            wgpu::BindGroupEntry { binding: 5, resource: wgpu::BindingResource::Sampler(grain_sampler) },
            wgpu::BindGroupEntry { binding: 6, resource: wgpu::BindingResource::TextureView(mask) },
            wgpu::BindGroupEntry { binding: 7, resource: wgpu::BindingResource::TextureView(second) },
            wgpu::BindGroupEntry { binding: 8, resource: wgpu::BindingResource::TextureView(clip_mask) },
            wgpu::BindGroupEntry { binding: 9, resource: wgpu::BindingResource::TextureView(paper) },
        ],
    })
}

/// The paper's tile ([`Canvas::set_paper`]): `TEXTURE_SIZE` square, a grey a pixel, every mip
/// level; at first all peak, taking all of the paint.
pub(crate) fn paper_texture(gpu: &Gpu) -> wgpu::Texture {
    let n = TEXTURE_SIZE as u32;
    let texture = gpu.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("paper"),
        size: extent(n, n),
        mip_level_count: n.ilog2() + 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::R8Unorm,
        usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
        view_formats: &[],
    });
    write_image_levels(gpu, &texture, 0, n, &vec![255; TEXTURE_SIZE * TEXTURE_SIZE]);
    texture
}

/// The layer `first` is clipped to, and that layer's mask: what compositing it reads.
pub(crate) fn clip_base_and_mask(doc: &Document, first: LayerId) -> impl Iterator<Item = LayerId> {
    let base = doc.clip_base(first);
    base.into_iter().chain(base.and_then(|b| doc.mask_of(b)))
}

/// The layers at the start of `rest` (items of a level, bottom up, from just above `base`)
/// clipped to `base`: with it, a clipping group.
pub(crate) fn clipped_run<'a>(doc: &Document, rest: &'a [LayerId], base: LayerId) -> &'a [LayerId] {
    let clipped =
        |id: LayerId| doc.layer(id).is_some_and(|l| l.kind == LayerKind::Paint) && doc.clip_base(id) == Some(base);
    let n = rest.iter().take_while(|id| clipped(**id)).count();
    rest.get(..n).unwrap_or_default()
}

/// A clipping group as it composites: its base and how that shows, the layers clipped to it (just
/// above it), and how each of those shows (none: not at all).
pub(crate) struct ClipGroup<'a> {
    pub(crate) base: &'a Layer,
    pub(crate) look: Look,
    pub(crate) clipped: &'a [LayerId],
    pub(crate) looks: &'a dyn Fn(LayerId) -> Option<Look>,
}

/// How many images of the user's own each of the shape and grain arrays holds at once.
pub(crate) const SOURCE_SLOTS: usize = 8;

/// How many brush files' grains stay on the GPU at their own size.
pub(crate) const FULL_GRAINS_HELD: usize = 3;

/// The most shape images a stroke's own set holds (see [`Canvas::use_tip_set`]).
pub const MOST_TIPS: u32 = 255;

/// What a stroke's own shapes are, and how they colour its stamps.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum TipKind {
    /// How much each pixel paints, in the stroke's colour.
    #[default]
    Mask = 0,
    /// Pictures: each pixel paints its own colour, as far as it is opaque.
    Picture = 1,
    /// Each pixel's grey sets the colour's lightness (a middle grey keeping it as it is, as far as
    /// the stamp's lightness strength goes), as far as it is opaque.
    Lightness = 2,
    /// Each pixel's grey picks a colour between the stroke's (black) and its secondary colour
    /// (white), as far as it is opaque.
    Gradient = 3,
}

impl TipKind {
    /// Bytes a pixel of its images take.
    pub fn channels(self) -> u32 {
        if self == TipKind::Mask { 1 } else { 4 }
    }
}

/// Brush files' grains or shapes at their own size, each an array with its mip levels, the image in its first
/// layer: only the last few used, so that their memory doesn't grow with the brushes.
#[derive(Default)]
pub(crate) struct FullGrains {
    pub(crate) held: Vec<(u64, wgpu::TextureView, u64)>,
    pub(crate) clock: u64,
}

impl FullGrains {
    /// Has the image `id` ready; false if `pixels` aren't `side` square.
    pub(crate) fn hold(&mut self, gpu: &Gpu, id: u64, side: u32, pixels: &[u8]) -> bool {
        self.hold_set(gpu, id, side, &[pixels], 1)
    }

    /// Has the images `id` ready, one to a layer in their order, `channels` bytes a pixel (1 grey,
    /// 4 RGBA straight, held premultiplied); false if any isn't `side` square, there are none or
    /// more than [`MOST_TIPS`].
    pub(crate) fn hold_set(&mut self, gpu: &Gpu, id: u64, side: u32, images: &[&[u8]], channels: u32) -> bool {
        self.clock += 1;
        let clock = self.clock;
        if let Some(slot) = self.held.iter_mut().find(|slot| slot.0 == id) {
            slot.2 = clock;
            return true;
        }
        let square = (side as usize).pow(2) * channels as usize;
        let most = MOST_TIPS.min(gpu.device.limits().max_texture_array_layers.saturating_sub(1)) as usize;
        if !side.is_power_of_two()
            || !matches!(channels, 1 | 4)
            || images.is_empty()
            || images.len() > most
            || images.iter().any(|i| i.len() != square)
        {
            return false;
        }
        let layers = gpu.array_layers(images.len() as u32).max(gpu.array_layers_least());
        let texture = gpu.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("full grain"),
            size: wgpu::Extent3d { width: side, height: side, depth_or_array_layers: layers },
            mip_level_count: side.ilog2() + 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: if channels == 4 { wgpu::TextureFormat::Rgba8Unorm } else { wgpu::TextureFormat::R8Unorm },
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        for (layer, pixels) in images.iter().enumerate() {
            if channels == 4 {
                // premultiplied, so that its smaller levels don't darken its edges
                let premultiplied: Vec<u8> = pixels
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .flat_map(|&[r, g, b, a]| {
                        let by = |c: u8| ((u32::from(c) * u32::from(a) + 127) / 255) as u8;
                        [by(r), by(g), by(b), a]
                    })
                    .collect();
                write_image_levels_of(gpu, &texture, layer as u32, side, &premultiplied, 4);
            } else {
                write_image_levels(gpu, &texture, layer as u32, side, pixels);
            }
        }
        if self.held.len() >= FULL_GRAINS_HELD
            && let Some(oldest) = self.held.iter().enumerate().min_by_key(|(_, slot)| slot.2).map(|(i, _)| i)
        {
            self.held.swap_remove(oldest);
        }
        self.held.push((id, array_view(&texture), clock));
        true
    }

    pub(crate) fn view(&self, id: u64) -> Option<&wgpu::TextureView> {
        self.held.iter().find(|slot| slot.0 == id).map(|slot| &slot.1)
    }
}

/// The spare layers of a brush image array, after the shipped images: which image of the user's
/// own each holds, and when it was last used (the longest unused gives way).
pub(crate) struct SourceSlots {
    pub(crate) first: u32,
    pub(crate) held: Vec<(u64, u64)>,
    pub(crate) clock: u64,
}

impl SourceSlots {
    pub(crate) fn after(shipped: usize) -> SourceSlots {
        SourceSlots { first: shipped as u32, held: Vec::new(), clock: 0 }
    }

    /// The layer of `texture` holding `source`, put there if it isn't.
    pub(crate) fn layer(&mut self, gpu: &Gpu, texture: &wgpu::Texture, source: &Source) -> u32 {
        self.clock += 1;
        let clock = self.clock;
        if let Some(i) = self.held.iter().position(|&(id, _)| id == source.id) {
            if let Some(slot) = self.held.get_mut(i) {
                slot.1 = clock;
            }
            return self.first + i as u32;
        }
        let i = if self.held.len() < SOURCE_SLOTS {
            self.held.push((source.id, clock));
            self.held.len() - 1
        } else {
            let oldest = self.held.iter().enumerate().min_by_key(|(_, slot)| slot.1).map_or(0, |(i, _)| i);
            if let Some(slot) = self.held.get_mut(oldest) {
                *slot = (source.id, clock);
            }
            oldest
        };
        let layer = self.first + i as u32;
        write_brush_image(gpu, texture, layer, &source.pixels);
        layer
    }
}

/// One white texel standing in for an array of brush images, until a canvas wants them.
pub(crate) fn stand_in_images(gpu: &Gpu, label: &str) -> wgpu::Texture {
    let layers = gpu.array_layers_least();
    gpu.device.create_texture_with_data(
        &gpu.queue,
        &wgpu::TextureDescriptor {
            label: Some(label),
            size: wgpu::Extent3d { width: 1, height: 1, depth_or_array_layers: layers },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::R8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        },
        wgpu::util::TextureDataOrder::LayerMajor,
        &vec![255; layers as usize],
    )
}

/// Greyscale brush images as one array texture with a full mip chain (each level a 2×2 average
/// of the one above), with [`SOURCE_SLOTS`] spare layers after them.
pub(crate) fn brush_images(gpu: &Gpu, label: &str, images: &[GrayImage]) -> wgpu::Texture {
    let n = TEXTURE_SIZE as u32;
    let texture = gpu.device.create_texture(&wgpu::TextureDescriptor {
        label: Some(label),
        size: wgpu::Extent3d {
            width: n,
            height: n,
            depth_or_array_layers: gpu.array_layers((images.len() + SOURCE_SLOTS) as u32),
        },
        mip_level_count: n.ilog2() + 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::R8Unorm,
        usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
        view_formats: &[],
    });
    for (layer, image) in images.iter().enumerate() {
        write_brush_image(gpu, &texture, layer as u32, &image.pixels);
    }
    texture
}

/// One brush image (`TEXTURE_SIZE` square) into `layer` of `texture`, every mip level.
pub(crate) fn write_brush_image(gpu: &Gpu, texture: &wgpu::Texture, layer: u32, pixels: &[u8]) {
    write_image_levels(gpu, texture, layer, TEXTURE_SIZE as u32, pixels);
}

/// A greyscale image `n` square into `layer` of `texture`, every mip level.
pub(crate) fn write_image_levels(gpu: &Gpu, texture: &wgpu::Texture, layer: u32, n: u32, pixels: &[u8]) {
    write_image_levels_of(gpu, texture, layer, n, pixels, 1);
}

/// [`write_image_levels`] for an image of `channels` bytes a pixel.
pub(crate) fn write_image_levels_of(gpu: &Gpu, texture: &wgpu::Texture, layer: u32, n: u32, pixels: &[u8], channels: u32) {
    let mut level = pixels.to_vec();
    let mut size = n;
    for mip in 0..n.ilog2() + 1 {
        gpu.queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture,
                mip_level: mip,
                origin: wgpu::Origin3d { x: 0, y: 0, z: layer },
                aspect: wgpu::TextureAspect::All,
            },
            &level,
            wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(size * channels), rows_per_image: Some(size) },
            wgpu::Extent3d { width: size, height: size, depth_or_array_layers: 1 },
        );
        if size > 1 {
            level = if channels == 1 {
                half_size(&level, size as usize)
            } else {
                half_size_of(&level, size as usize, channels as usize)
            };
            size /= 2;
        }
    }
}

/// [`half_size`] for an image of `channels` bytes a pixel, each channel on its own.
pub(crate) fn half_size_of(pixels: &[u8], size: usize, channels: usize) -> Vec<u8> {
    let half = size / 2;
    let at = |x: usize, y: usize, c: usize| u32::from(pixels.get((y * size + x) * channels + c).copied().unwrap_or(0));
    let mut out = Vec::with_capacity(half * half * channels);
    for y in 0..half {
        for x in 0..half {
            for c in 0..channels {
                let sum = at(2 * x, 2 * y, c)
                    + at(2 * x + 1, 2 * y, c)
                    + at(2 * x, 2 * y + 1, c)
                    + at(2 * x + 1, 2 * y + 1, c);
                out.push(((sum + 2) / 4) as u8);
            }
        }
    }
    out
}

/// A square greyscale image at half the size: each pixel the average of four.
pub(crate) fn half_size(pixels: &[u8], size: usize) -> Vec<u8> {
    let half = size / 2;
    let at = |x: usize, y: usize| u32::from(pixels.get(y * size + x).copied().unwrap_or(0));
    let mut out = Vec::with_capacity(half * half);
    for y in 0..half {
        for x in 0..half {
            let sum = at(2 * x, 2 * y) + at(2 * x + 1, 2 * y) + at(2 * x, 2 * y + 1) + at(2 * x + 1, 2 * y + 1);
            out.push(((sum + 2) / 4) as u8);
        }
    }
    out
}

/// The stamp shader's shapes, grains and selection, and its two samplers.
pub(crate) fn brush_bind(
    device: &wgpu::Device,
    layout: &wgpu::BindGroupLayout,
    [shapes, grains, mask]: [&wgpu::TextureView; 3],
    [shape_sampler, grain_sampler]: [&wgpu::Sampler; 2],
) -> wgpu::BindGroup {
    device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("shapes, grains and the selection"),
        layout,
        entries: &[
            wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(shapes) },
            wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(grains) },
            wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::Sampler(shape_sampler) },
            wgpu::BindGroupEntry { binding: 3, resource: wgpu::BindingResource::Sampler(grain_sampler) },
            wgpu::BindGroupEntry { binding: 4, resource: wgpu::BindingResource::TextureView(mask) },
        ],
    })
}

/// A one-channel texture of coverage, `width` × `height`, from `data` (one byte a pixel).
/// The rectangle holding every pixel `rows` (whole rows of a coverage `width` pixels wide, the
/// first of them row `first`) selects any of; empty where none is.
pub(crate) fn coverage_bounds(rows: &[u8], width: u32, first: usize) -> IRect {
    let w = (width as usize).max(1);
    let mut bounds = IRect::EMPTY;
    for (j, row) in rows.chunks(w).enumerate() {
        let (Some(left), Some(right)) = (row.iter().position(|&c| c > 0), row.iter().rposition(|&c| c > 0)) else {
            continue;
        };
        let y = i32::try_from(first + j).unwrap_or(i32::MAX);
        let at = |x: usize| i32::try_from(x).unwrap_or(i32::MAX);
        bounds = bounds.union(IRect::new(at(left), y, at(right).saturating_add(1), y.saturating_add(1)));
    }
    bounds
}

pub(crate) fn coverage_texture(device: &wgpu::Device, queue: &wgpu::Queue, width: u32, height: u32, data: &[u8]) -> wgpu::Texture {
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("selection"),
        size: extent(width, height),
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::R8Unorm,
        usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
        view_formats: &[],
    });
    queue.write_texture(
        wgpu::TexelCopyTextureInfo {
            texture: &texture,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        data,
        wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(width), rows_per_image: Some(height) },
        extent(width, height),
    );
    texture
}

/// A 3D colour table `grid` on a side (RGBA8, blue varying fastest, then green, then red) and
/// its sampler, bound for the display.
pub(crate) fn lut_bind(gpu: &Gpu, layout: &wgpu::BindGroupLayout, grid: u32, rgba: &[u8]) -> wgpu::BindGroup {
    let size = wgpu::Extent3d { width: grid, height: grid, depth_or_array_layers: grid };
    let texture = gpu.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("colour table"),
        size,
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D3,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
        view_formats: &[],
    });
    gpu.queue.write_texture(
        wgpu::TexelCopyTextureInfo {
            texture: &texture,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        rgba,
        wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(4 * grid), rows_per_image: Some(grid) },
        size,
    );
    let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
    let sampler = gpu.device.create_sampler(&wgpu::SamplerDescriptor {
        label: Some("colour table"),
        mag_filter: wgpu::FilterMode::Linear,
        min_filter: wgpu::FilterMode::Linear,
        ..Default::default()
    });
    gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("colour table"),
        layout,
        entries: &[
            wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&view) },
            wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&sampler) },
        ],
    })
}

pub(crate) fn sampled_bind(
    device: &wgpu::Device,
    layout: &wgpu::BindGroupLayout,
    view: &wgpu::TextureView,
    sampler: &wgpu::Sampler,
) -> wgpu::BindGroup {
    device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("sampled"),
        layout,
        entries: &[
            wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(view) },
            wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(sampler) },
        ],
    })
}

pub(crate) fn uniforms(device: &wgpu::Device, layout: &wgpu::BindGroupLayout, slots: u64) -> (wgpu::Buffer, wgpu::BindGroup) {
    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("uniforms"),
        size: slots * SLOT,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("uniforms"),
        layout,
        entries: &[wgpu::BindGroupEntry {
            binding: 0,
            resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                buffer: &buffer,
                offset: 0,
                size: NonZeroU64::new(UNIFORM_SIZE),
            }),
        }],
    });
    (buffer, bind)
}

/// Uniform slots for `count` smudge stamps.
pub(crate) fn spare_buffer(device: &wgpu::Device) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("smudge spare"),
        size: 16,
        usage: wgpu::BufferUsages::STORAGE,
        mapped_at_creation: false,
    })
}

pub(crate) fn wet_params(device: &wgpu::Device, count: u64) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("wet passes"),
        size: count.max(1) * WET_PASS_SIZE.div_ceil(SLOT) * SLOT,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

pub(crate) fn smudge_params(device: &wgpu::Device, count: u64) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("smudge stamps"),
        size: count.max(1) * SLOT,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

pub(crate) fn stamp_buffer(device: &wgpu::Device, capacity: u64) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("stamps"),
        size: capacity.max(1) * size_of::<StampInstance>() as u64,
        // (read as storage by the stamps laid by their stroke's rule)
        usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

pub(crate) fn render_pass<'e>(
    encoder: &'e mut wgpu::CommandEncoder,
    label: &str,
    view: &wgpu::TextureView,
    load: wgpu::LoadOp<wgpu::Color>,
) -> wgpu::RenderPass<'e> {
    encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
        label: Some(label),
        color_attachments: &[Some(wgpu::RenderPassColorAttachment {
            view,
            depth_slice: None,
            resolve_target: None,
            ops: wgpu::Operations { load, store: wgpu::StoreOp::Store },
        })],
        depth_stencil_attachment: None,
        timestamp_writes: None,
        occlusion_query_set: None,
        multiview_mask: None,
    })
}

pub(crate) fn scissor(pass: &mut wgpu::RenderPass<'_>, r: IRect) {
    let x = u32::try_from(r.x0).unwrap_or(0);
    let y = u32::try_from(r.y0).unwrap_or(0);
    pass.set_scissor_rect(x, y, r.width(), r.height());
}

pub(crate) fn extent(width: u32, height: u32) -> wgpu::Extent3d {
    wgpu::Extent3d { width: width.max(1), height: height.max(1), depth_or_array_layers: 1 }
}

pub(crate) fn copy_at(texture: &wgpu::Texture, x: i32, y: i32) -> wgpu::TexelCopyTextureInfo<'_> {
    wgpu::TexelCopyTextureInfo {
        texture,
        mip_level: 0,
        origin: wgpu::Origin3d { x: u32::try_from(x).unwrap_or(0), y: u32::try_from(y).unwrap_or(0), z: 0 },
        aspect: wgpu::TextureAspect::All,
    }
}

pub(crate) fn srgb_to_linear(c: f32) -> f32 {
    if c <= 0.04045 { c / 12.92 } else { ((c + 0.055) / 1.055).powf(2.4) }
}

/// Reads a texture of 4-byte pixels back from the GPU, rows top to bottom without padding.
pub fn read_texture(gpu: &Gpu, texture: &wgpu::Texture, width: u32, height: u32) -> Result<Vec<u8>, GpuError> {
    start_reading(gpu, texture, width, height).pixels()
}

/// Pixels on their way back from the GPU: their copy is queued (under whatever lock guards what
/// they are copied from), and waited for later, the lock let go ([`Readback::pixels`]).
pub enum Readback {
    /// Already here.
    Ready(Vec<u8>),
    /// The copy couldn't be made (why): nothing is coming.
    Failed(String),
    Copying {
        device: wgpu::Device,
        /// Whether the device has been lost (see [`Gpu::is_lost`]).
        lost: Arc<AtomicBool>,
        /// The buffer and how much of it the pixels take; kept for the next readback after.
        buffer: wgpu::Buffer,
        size: u64,
        kept: Arc<Mutex<Vec<wgpu::Buffer>>>,
        /// A row's bytes, and as the copy pads it.
        row: usize,
        padded: usize,
        mapped: mpsc::Receiver<Result<(), wgpu::BufferAsyncError>>,
    },
}

/// The layers' coverage at a point, as [`Canvas::start_alphas_at`] started reading it.
#[derive(Default)]
pub struct PendingAlphas {
    /// The paint layers in the document's order, those read from the GPU, and the parked ones'.
    pub(crate) paint: Vec<LayerId>,
    pub(crate) ids: Vec<LayerId>,
    pub(crate) parked: HashMap<LayerId, u8>,
    pub(crate) read: Option<Readback>,
}

impl PendingAlphas {
    /// Each paint layer's coverage (alpha, 0 to 255), in the document's order, once the GPU has
    /// copied it.
    pub fn alphas(self) -> Result<Vec<(LayerId, u8)>, GpuError> {
        let read: HashMap<LayerId, u8> = match self.read {
            Some(read) => {
                let pixels = read.pixels()?;
                self.ids.iter().enumerate().map(|(i, id)| (*id, pixels.get(4 * i + 3).copied().unwrap_or(0))).collect()
            }
            None => HashMap::new(),
        };
        Ok(self.paint.iter().filter_map(|id| Some((*id, *read.get(id).or_else(|| self.parked.get(id))?))).collect())
    }
}

/// The most bytes of readback buffers kept: two layers the size of a large tablet canvas.
pub(crate) const READBACKS_KEPT: u64 = 96 << 20;

impl Readback {
    /// How many bytes the pixels take.
    pub fn bytes(&self) -> usize {
        match self {
            Readback::Ready(p) => p.len(),
            Readback::Failed(_) => 0,
            Readback::Copying { size, .. } => usize::try_from(*size).unwrap_or(usize::MAX),
        }
    }

    /// The pixels (4 bytes each, rows top to bottom without padding), once the GPU has copied
    /// them.
    pub fn pixels(self) -> Result<Vec<u8>, GpuError> {
        let (device, buffer, size, kept, row, padded, mapped) = match self {
            Readback::Ready(p) => return Ok(p),
            Readback::Failed(why) => return Err(GpuError::Readback(why)),
            Readback::Copying { device, lost, buffer, size, kept, row, padded, mapped } => {
                if lost.load(Ordering::Relaxed) {
                    return Err(GpuError::Lost);
                }
                (device, buffer, size, kept, row, padded, mapped)
            }
        };
        // a device lost or out of memory meanwhile is an error, not a panic as in wgpu's own wait
        crate::poll(&device, wgpu::PollType::wait_indefinitely())?;
        mapped.recv().map_err(|e| GpuError::Readback(e.to_string()))?.map_err(|e| GpuError::Readback(e.to_string()))?;
        let view = buffer.slice(..size).get_mapped_range().map_err(|e| GpuError::Readback(e.to_string()))?;
        let mut out = Vec::with_capacity(row * (view.len() / padded.max(1)));
        for chunk in view.chunks(padded.max(1)) {
            out.extend_from_slice(chunk.get(..row).unwrap_or(chunk));
        }
        drop(view);
        buffer.unmap();
        // kept for the next readback while there is room
        let mut kept = kept.lock().unwrap_or_else(PoisonError::into_inner);
        if kept.iter().map(wgpu::Buffer::size).sum::<u64>().saturating_add(buffer.size()) <= READBACKS_KEPT {
            kept.push(buffer);
        }
        Ok(out)
    }
}

/// How many layers `width` x `height` a canvas keeps on the GPU at once with textures' budget
/// `budget` bytes (0: no limit; one at least).
pub fn most_resident(budget: u64, width: u32, height: u32) -> usize {
    let per = u64::from(width) * u64::from(height) * 4;
    if budget == 0 { usize::MAX } else { usize::try_from(budget / per.max(1)).unwrap_or(usize::MAX).max(1) }
}

/// Starts reading a texture of 4-byte pixels back from the GPU: its copy is queued now.
pub fn start_reading(gpu: &Gpu, texture: &wgpu::Texture, width: u32, height: u32) -> Readback {
    start_reading_part(gpu, texture, IRect::from_size(width, height))
}

/// [`start_reading`] for the pixels inside `rect` (on the texture, not empty) only.
pub(crate) fn start_reading_part(gpu: &Gpu, texture: &wgpu::Texture, rect: IRect) -> Readback {
    let (width, height) = (rect.width(), rect.height());
    let row = width as usize * 4;
    let padded = row.div_ceil(256) * 256;
    let size = (padded * height as usize) as u64;
    let buffer = gpu.readback_buffer(size);
    // a texture there was no memory for (or one its device let go) copies nothing, and the buffer
    // would give back what it held before, another layer's pixels perhaps: the read fails instead
    let invalid = gpu.device.push_error_scope(wgpu::ErrorFilter::Validation);
    let mut encoder = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("readback") });
    gpu.copy_texture_to_buffer(
        &mut encoder,
        copy_at(texture, rect.x0, rect.y0),
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(padded as u32),
                rows_per_image: Some(height),
            },
        },
        extent(width, height),
    );
    gpu.queue.submit([encoder.finish()]);
    if let Some(e) = pollster::block_on(invalid.pop()) {
        return Readback::Failed(e.to_string());
    }
    let (tx, rx) = mpsc::channel();
    buffer.slice(..size).map_async(wgpu::MapMode::Read, move |r| {
        let _ = tx.send(r);
    });
    let kept = gpu.readbacks.clone();
    let (device, lost) = (gpu.device.clone(), gpu.lost.clone());
    Readback::Copying { device, lost, buffer, size, kept, row, padded, mapped: rx }
}
