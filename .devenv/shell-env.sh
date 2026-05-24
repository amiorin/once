PATH=${PATH:-}
nix_saved_PATH="$PATH"
XDG_DATA_DIRS=${XDG_DATA_DIRS:-}
nix_saved_XDG_DATA_DIRS="$XDG_DATA_DIRS"
declare -a propagatedBuildDepFiles=('propagated-build-build-deps' 'propagated-native-build-inputs' 'propagated-build-target-deps' )
buildPhase='{ echo "------------------------------------------------------------";
  echo " WARNING: the existence of this path is not guaranteed.";
  echo " It is an internal implementation detail for pkgs.mkShell.";
  echo "------------------------------------------------------------";
  echo;
  # Record all build inputs as runtime dependencies
  export;
} >> "$out"
'
export buildPhase
MACHTYPE='aarch64-unknown-linux-gnu'
NIX_CFLAGS_COMPILE=' -frandom-seed=3dxqb73nvr -isystem /nix/store/af91fyhrr83yjmv0hvmg8qwllw3qk5mg-bash-interactive-5.3p9-dev/include -isystem /nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env/include -isystem /nix/store/s80zvr4vw2qlcnxzmbpd71b4ay7grfrj-openjdk-21.0.10+7/include -isystem /nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12/include -isystem /nix/store/659dw98ywa9f4w6gajp7sgbac49fgla5-libssh-0.12.0-dev/include -isystem /nix/store/af91fyhrr83yjmv0hvmg8qwllw3qk5mg-bash-interactive-5.3p9-dev/include -isystem /nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env/include -isystem /nix/store/s80zvr4vw2qlcnxzmbpd71b4ay7grfrj-openjdk-21.0.10+7/include -isystem /nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12/include -isystem /nix/store/659dw98ywa9f4w6gajp7sgbac49fgla5-libssh-0.12.0-dev/include'
export NIX_CFLAGS_COMPILE
NIX_NO_SELF_RPATH='1'
CC='gcc'
export CC
DEVENV_PROFILE='/nix/store/64ajws1as10kx24jrzs4kjqjlchy9pim-devenv-profile'
export DEVENV_PROFILE
depsBuildBuildPropagated=''
export depsBuildBuildPropagated
declare -a envHostHostHooks=('addPkgToClassPath' 'pkgConfigWrapper_addPkgConfigPath' 'ccWrapper_addCVars' 'bintoolsWrapper_addLDVars' )
RANLIB='ranlib'
export RANLIB
SIZE='size'
export SIZE
STRINGS='strings'
export STRINGS
defaultNativeBuildInputs='/nix/store/0nhm91rg41gx04lzck4mv24wwhfw7yb4-patchelf-0.15.2 /nix/store/4ljh596cv90vcy494iin0ssr8mc1i0ch-update-autotools-gnu-config-scripts-hook /nix/store/0y5xmdb7qfvimjwbq7ibg1xdgkgjwqng-no-broken-symlinks.sh /nix/store/cv1d7p48379km6a85h4zp6kr86brh32q-audit-tmpdir.sh /nix/store/85clx3b0xkdf58jn161iy80y5223ilbi-compress-man-pages.sh /nix/store/p3l1a5y7nllfyrjn2krlwgcc3z0cd3fq-make-symlinks-relative.sh /nix/store/5yzw0vhkyszf2d179m0qfkgxmp5wjjx4-move-docs.sh /nix/store/fyaryjvghbkpfnsyw97hb3lyb37s1pd6-move-lib64.sh /nix/store/kd4xwxjpjxi71jkm6ka0np72if9rm3y0-move-sbin.sh /nix/store/pag6l61paj1dc9sv15l7bm5c17xn5kyk-move-systemd-user-units.sh /nix/store/cmzya9irvxzlkh7lfy6i82gbp0saxqj3-multiple-outputs.sh /nix/store/x8c40nfigps493a07sdr2pm5s9j1cdc0-patch-shebangs.sh /nix/store/cickvswrvann041nqxb0rxilc46svw1n-prune-libtool-files.sh /nix/store/xyff06pkhki3qy1ls77w10s0v79c9il0-reproducible-builds.sh /nix/store/z7k98578dfzi6l3hsvbivzm7hfqlk0zc-set-source-date-epoch-to-latest.sh /nix/store/pilsssjjdxvdphlg2h19p0bfx5q0jzkn-strip.sh /nix/store/xvvipinc7wngr82akl5i61bxdh10rajw-gcc-wrapper-15.2.0'
outputDev='out'
declare -a pkgsBuildBuild=()
name='devenv-shell-env'
export name
NIX_BINTOOLS_WRAPPER_TARGET_HOST_aarch64_unknown_linux_gnu='1'
export NIX_BINTOOLS_WRAPPER_TARGET_HOST_aarch64_unknown_linux_gnu
AS='as'
export AS
declare -a pkgsHostTarget=()
DEVENV_TASK_FILE='/nix/store/pibqjrpcvm12axc6vg5hhhg4h90dvxkk-tasks.json'
export DEVENV_TASK_FILE
OBJCOPY='objcopy'
export OBJCOPY
DEVENV_TASKS=''
export DEVENV_TASKS
phases='buildPhase'
export phases
NIX_BUILD_CORES='3'
export NIX_BUILD_CORES
depsBuildTarget=''
export depsBuildTarget
OPTERR='1'
OSTYPE='linux-gnu'
declare -a envBuildBuildHooks=('sysconfigdataHook' )
HOSTTYPE='aarch64'
_substituteStream_has_warned_replace_deprecation='false'
nativeBuildInputs='/nix/store/af91fyhrr83yjmv0hvmg8qwllw3qk5mg-bash-interactive-5.3p9-dev /nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env /nix/store/719v6ih5y46l6fnp3cbr3ylyw0pnx542-uv-0.11.11 /nix/store/27znl24k4ah0is0n48z0za8m4l4hza8d-pyright-1.1.409 /nix/store/bbx49kfdnrmv74mk89js0i49r3l1yi7l-opentofu-1.11.6 /nix/store/ibbdy7d8gi2scr6kjw4y9bg9awzcad4h-terraform-ls-0.38.6 /nix/store/s80zvr4vw2qlcnxzmbpd71b4ay7grfrj-openjdk-21.0.10+7 /nix/store/ijajhr7gdrp53v2mdw4x94sbbjd9fxb0-jdt-language-server-1.58.0 /nix/store/alsq5bbd7ich0jywypyhyca28x2dpr5n-clojure-1.12.4.1629 /nix/store/zfi24gly7kw7g7ykw0qnqcjw9jwpvmhy-clojure-lsp-2025.11.28-12.47.43 /nix/store/08q9n2bimkxw80cf7msajb5scpgqhjpq-ansible-lint-25.8.2 /nix/store/isq8ia232pzg3d0j8jirpzg0vk2rkwqr-python3.13-ansible-core-2.20.5 /nix/store/pzlffjn6pfpjqnamc8azchvwrl9ysvk5-ansible-language-server-1.2.4 /nix/store/yylmcmb481m97csjylklb4s61licv408-babashka-1.12.218 /nix/store/m7a58rxv258bnix9ns2rkgniskyzkq9h-jet-0.7.27 /nix/store/s1y1s3pij9bilz4z8zphjjwchkcyrr34-hcl2json-0.6.9 /nix/store/y48ygqd28j468i7abfh9v5yy1rvj6qc2-awscli2-2.34.24 /nix/store/137ddyf6621kbv22vrdv7rmhn75a78dh-skopeo-1.22.2 /nix/store/bwn8vhycr7xkf9bkf2l980sxw44gid3p-hcloud-1.64.1 /nix/store/dspm0p0k5lvl5q8pyn61n12vwnvdq8ip-doctl-1.157.0 /nix/store/kgz9d6ywgjb2wswd1bw5in3ffzs3mdqw-pkg-config-wrapper-0.29.2'
export nativeBuildInputs
PS4='+ '
declare -a pkgsBuildHost=('/nix/store/af91fyhrr83yjmv0hvmg8qwllw3qk5mg-bash-interactive-5.3p9-dev' '/nix/store/kqh7aw6sj1b0kzfkx2fxm8ij4lkc0735-bash-interactive-5.3p9' '/nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env' '/nix/store/719v6ih5y46l6fnp3cbr3ylyw0pnx542-uv-0.11.11' '/nix/store/27znl24k4ah0is0n48z0za8m4l4hza8d-pyright-1.1.409' '/nix/store/bbx49kfdnrmv74mk89js0i49r3l1yi7l-opentofu-1.11.6' '/nix/store/ibbdy7d8gi2scr6kjw4y9bg9awzcad4h-terraform-ls-0.38.6' '/nix/store/s80zvr4vw2qlcnxzmbpd71b4ay7grfrj-openjdk-21.0.10+7' '/nix/store/4msyanzj82nj93s6asvvcc4xpf3gz3a1-set-java-classpath-hook' '/nix/store/ijajhr7gdrp53v2mdw4x94sbbjd9fxb0-jdt-language-server-1.58.0' '/nix/store/alsq5bbd7ich0jywypyhyca28x2dpr5n-clojure-1.12.4.1629' '/nix/store/zfi24gly7kw7g7ykw0qnqcjw9jwpvmhy-clojure-lsp-2025.11.28-12.47.43' '/nix/store/08q9n2bimkxw80cf7msajb5scpgqhjpq-ansible-lint-25.8.2' '/nix/store/isq8ia232pzg3d0j8jirpzg0vk2rkwqr-python3.13-ansible-core-2.20.5' '/nix/store/1mdldq2x95ri0vizys041q97hy1v5x17-python3.13-ansible-13.5.0' '/nix/store/9j3jgf69c4xdiy46hl3zrz43bc24hl2h-python3.13-passlib-1.9.3' '/nix/store/gmblcdr4na80l53jbaa4gdj9f8sds6m8-python3.13-libpass-1.9.3' '/nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12' '/nix/store/3hf7jlvl9mv1kisw82snrgrizi5zw8wa-python3.13-jxmlease-1.0.3' '/nix/store/izw9v90niav9g55nz048jx1gp28lg6r7-python3.13-lxml-6.0.2' '/nix/store/1jrvvxv1m7l8hzhm6hf661qfxagby0f9-python3.13-ncclient-0.7.1' '/nix/store/n8gi3p5jlsp2md7wgmjpyhns8wcfzzjd-python3.13-paramiko-4.0.0' '/nix/store/qngvjm11a5b4cv0gbah8511sx5s0zjv0-python3.13-bcrypt-5.0.0' '/nix/store/a6jlwaj4aw05cxl9dhd41z7prmf9knki-python3.13-cryptography-46.0.6' '/nix/store/vbw4sc3aqqss025crfdg6na13qgy6q8v-python3.13-cffi-2.0.0' '/nix/store/zw9ys1n9mynhnmm3qn1ai83jw9rzj56f-python3.13-pycparser-3.00' '/nix/store/i4yqhlnisq1d4r7a50knpm9sqdbfckxh-python3.13-pynacl-1.6.2' '/nix/store/xmfxad16dz6dvm929nbhm297vhhgdizp-python3.13-netaddr-1.3.0' '/nix/store/q820s8pmaiqvjh3c5qpr3vnh5qi1s5yn-python3.13-ansible-pylibssh-1.4.0' '/nix/store/659dw98ywa9f4w6gajp7sgbac49fgla5-libssh-0.12.0-dev' '/nix/store/f6ngrk2nkc04xf4vw5awdh5lrw3iq5h0-libssh-0.12.0' '/nix/store/sc4av3dlxl0zk3zdbrir7qzn5j3ih7kh-python3.13-xmltodict-1.0.2' '/nix/store/c7q33sqpzvws26y7nvfkpy5hds7yha78-python3.13-jsonschema-4.26.0' '/nix/store/vdq5i06zj64ks1xmzgki8mil27wqh9r8-python3.13-attrs-25.4.0' '/nix/store/brpyvajffns6603982jwx1vd5kfq0plh-python3.13-jsonpath-ng-1.7.0' '/nix/store/b62rc7l5sbl07nc6h1fm5533axwp6mr9-python3.13-ply-3.11' '/nix/store/7xib1gh01400ji1bcbdnxq9n6am8nd8i-python3.13-jsonschema-specifications-2025.9.1' '/nix/store/04xzvkihv2khs5bwrb3adraxnkzzi5r3-python3.13-referencing-0.37.0' '/nix/store/x5pnxwfgkjip1qklhgilc3r9rxa7bcv7-python3.13-rpds-py-0.30.0' '/nix/store/3w5wcn0bjii89bs61zfw5zj1l3m6qaww-python3.13-typing-extensions-4.15.0' '/nix/store/ma9z3cyw12ysj1l7ha9rmdl1zvr8bs6z-python3.13-textfsm-2.1.0' '/nix/store/am13qlg6mvj77p1v0sgb7rkh67azqzi6-python3.13-ttp-0.10.1' '/nix/store/6mxbva4wlwwbwk4d80g26qgm0qdcs7fn-python3.13-cerberus-1.3.8' '/nix/store/kxdylp9hpjaw73dff7vji8rj5jkydzk6-python3.13-configparser-7.2.0' '/nix/store/nv76d7p4v6qvrbb1qzqgyp9fhlq0fmmc-python3.13-deepdiff-8.6.2' '/nix/store/7a53x814jbbm5m6k0b98hzkzdll0aakg-python3.13-orderly-set-5.5.0' '/nix/store/gflwgn63d560dvkv0qmy7f3qcldgvi4g-python3.13-geoip2-5.2.0' '/nix/store/828x6n9sk3wqp6l1301s1v74fqr97bxp-python3.13-aiohttp-3.13.4' '/nix/store/m09w3y0p2j0i0p696iy45hd51g1ih15j-python3.13-aiohappyeyeballs-2.6.1' '/nix/store/wnhdy6nnq3dn8nfi16h6pkkk41fd7bym-python3.13-aiosignal-1.4.0' '/nix/store/gp4yscidn3rqx566d0xxbw7p4dx8x80d-python3.13-frozenlist-1.8.0' '/nix/store/698kmbwnxc8llz7baszacxvfmxj60lvr-python3.13-multidict-6.7.1' '/nix/store/3nqbfjw1jcw6qyjikm1viwza16zsnakk-python3.13-propcache-0.4.1' '/nix/store/id80yx3rfhr7jsv16fbxlkjb8s7w06ix-python3.13-yarl-1.23.0' '/nix/store/l0wcsfrzpzjimrv53pp3l52zxaj60gv4-python3.13-idna-3.11' '/nix/store/j861cn0z01fdcvvmhjw8zy1l9rbjbj5z-python3.13-aiodns-4.0.0' '/nix/store/ijb4xzmhqz908i2qxihqyja6anph2yb5-python3.13-pycares-5.0.1' '/nix/store/z26nrx48d5970xq2nrxmq78cma2givwm-python3.13-brotli-1.2.0' '/nix/store/d2006k05px9jrbhdi3ivvhi7r2gmvw6k-python3.13-backports-zstd-1.3.0' '/nix/store/bb45s9p3nirvlq0qdxmkgcimmd6x8r85-python3.13-maxminddb-3.0.0' '/nix/store/7scgpzymw3hvb9hsbsjq8pvj7anzb446-python3.13-requests-2.33.1' '/nix/store/5mg1z2nvarlac2gw9rhmnwsqcwgpxxsl-python3.13-certifi-2026.01.04' '/nix/store/6216c8xaxbljnds9pg4x1zkpk3qg47yd-python3.13-charset-normalizer-3.4.4' '/nix/store/2zlinqx4l8prssk1bfjqf98hhg75rg0c-python3.13-urllib3-2.6.3' '/nix/store/9bf44cglpwsjl97j85mnf1pq9q6n0d24-python3.13-jinja2-3.1.6' '/nix/store/1wy09k8frkdz97s5bssyjg8zdscgmrda-python3.13-markupsafe-3.0.3' '/nix/store/yk0x8pc58nfy41anf9k72shw4jhim0yy-python3.13-netmiko-4.6.0' '/nix/store/pd69729lylbcvcd73l77xq8f4ahfgjs6-python3.13-ntc-templates-8.1.0' '/nix/store/2vmdlzn3r7brg6nxph2712rhbsxfmmdc-python3.13-pyserial-3.5' '/nix/store/p8sajismwcq0jc23g6jmbablbwj22hzj-python3.13-pyyaml-6.0.3' '/nix/store/x4rai0cn2l9izvd78r7z9nb0gcpi2mqx-python3.13-rich-14.3.3' '/nix/store/l1x8086qkk9pkh5afa8f862z5d975v4s-python3.13-markdown-it-py-4.0.0' '/nix/store/macp6ff4il51gh4yq4yav21kang9j9vq-python3.13-mdurl-0.1.2' '/nix/store/jbdvw6amh550rpdc7bi7i9ghbpj8pd78-python3.13-pygments-2.20.0' '/nix/store/m158x69j763nyfkrawg65rkkfkrrf2mk-python3.13-ruamel-yaml-0.19.1' '/nix/store/9cd8gyxfwymik3adpjn6a0xh6pdn34i5-python3.13-ruamel-base-1.0.0' '/nix/store/x9p1h8v7qhc8kwiwvlizsbr9ybgwv7nf-python3.13-ruamel-yaml-clib-0.2.15' '/nix/store/xbfqib3mz7ya8jz8w74nfccmc4d391dv-python3.13-scp-0.15.0' '/nix/store/7mz9i9xkbd2ilnk07gf9lcrc775d0yyk-python3.13-openpyxl-3.1.5' '/nix/store/a5kkjvmf4g75bf3p8h3f3cjazhbignag-python3.13-et-xmlfile-2.0.0' '/nix/store/7xm5yyjszy45k0vxb0qrnm8sjykqiar4-python3.13-tabulate-0.10.0' '/nix/store/r82grs16l3zc8m73yvpjdd28kbvjir01-python3.13-yangson-1.7.5' '/nix/store/6jp8ac8w9x6p27c7mp12z5qaj99irvb8-python3.13-elementpath-5.1.1' '/nix/store/wd2m3ciakgivi2iapjka0xs2p1viapcn-python3.13-jmespath-1.0.1' '/nix/store/x9a0hvz3gjvmiv1zffmvjmibpv0zs6px-python3.13-packaging-25.0' '/nix/store/81yl286f4cnlaq583djdknwpbwlrif5n-python3.13-resolvelib-1.2.1' '/nix/store/bvdw85r9j63lc5d53kl2mpz2l2dfkqni-python3.13-junit-xml-1.9' '/nix/store/6pv6p4zxgj05iylvh3clp5j0yijjz63w-python3.13-six-1.17.0' '/nix/store/89gyjjiq20hrlcyz54vdb7icxd7gcvbj-python3.13-pexpect-4.9.0' '/nix/store/bacdz48c2pfdvcvbk0m8qf0lw39k4ir5-python3.13-ptyprocess-0.7.0' '/nix/store/jch5ai3vfmgdis2c8ism9c7mavqdadvg-python3.13-psutil-7.2.2' '/nix/store/9v32k31792qf7bx34c2r4aiw4gczxll9-python3.13-pycrypto-3.23.0' '/nix/store/18qfzi89zxpanpxqrw3869d6x0lnb4hq-python3.13-pycryptodome-3.23.0' '/nix/store/p36wfpl2yg3qk7p0w8zal4gpd2v4a8r9-python3.13-ansible-compat-26.3.0' '/nix/store/70mjhwcrmim4p7pa5gln4cpayrbfyqx8-python3.13-subprocess-tee-0.4.2' '/nix/store/dkki51dvfkff891fs0yng4p751dz2wfq-python3.13-black-25.1.0' '/nix/store/z1pdwppy9nxf120i26mcm6x4hdz5ig8w-python3.13-click-8.3.1' '/nix/store/l5drqzb071qhm1l8a87g7m0wmspw8xw4-python3.13-mypy-extensions-1.1.0' '/nix/store/vil5kk6x5af0b9jpcmxq1nmvg5qnj0r1-python3.13-flit-core-3.12.0' '/nix/store/jk4fd3xp3pb6mv320lx9zpbwxdbibp6i-python3.13-pathspec-0.12.1' '/nix/store/1qi5l8763c85ldp4kfz40fjvgarwdp18-python3.13-platformdirs-4.5.1' '/nix/store/h98dj52qdgcnhny1qkszhwxxlmyqzl71-python3.13-filelock-3.20.3' '/nix/store/7q3j4x2aszkmniwlpjh1qanrgx35yi58-python3.13-importlib-metadata-9.0.0' '/nix/store/3v4jpxpdha0vahyz1pvd2nq5dvyj4w5i-python3.13-toml-0.10.2' '/nix/store/i96dnkal40ywkvf8ya9qixv5zrvyz17b-python3.13-zipp-3.23.1' '/nix/store/517spk64lv90p4y7hai3xqvbs1a0drjb-python3.13-wcmatch-10.1' '/nix/store/m8l8s0m5q3vlrvgdw0vwz70mxzkrf69d-python3.13-bracex-2.6' '/nix/store/m9g8qnggdzc6pjdw7w21vaywmlix2ccn-python3.13-yamllint-1.37.1' '/nix/store/pzlffjn6pfpjqnamc8azchvwrl9ysvk5-ansible-language-server-1.2.4' '/nix/store/yylmcmb481m97csjylklb4s61licv408-babashka-1.12.218' '/nix/store/m7a58rxv258bnix9ns2rkgniskyzkq9h-jet-0.7.27' '/nix/store/s1y1s3pij9bilz4z8zphjjwchkcyrr34-hcl2json-0.6.9' '/nix/store/y48ygqd28j468i7abfh9v5yy1rvj6qc2-awscli2-2.34.24' '/nix/store/137ddyf6621kbv22vrdv7rmhn75a78dh-skopeo-1.22.2' '/nix/store/bwn8vhycr7xkf9bkf2l980sxw44gid3p-hcloud-1.64.1' '/nix/store/dspm0p0k5lvl5q8pyn61n12vwnvdq8ip-doctl-1.157.0' '/nix/store/kgz9d6ywgjb2wswd1bw5in3ffzs3mdqw-pkg-config-wrapper-0.29.2' '/nix/store/0nhm91rg41gx04lzck4mv24wwhfw7yb4-patchelf-0.15.2' '/nix/store/4ljh596cv90vcy494iin0ssr8mc1i0ch-update-autotools-gnu-config-scripts-hook' '/nix/store/0y5xmdb7qfvimjwbq7ibg1xdgkgjwqng-no-broken-symlinks.sh' '/nix/store/cv1d7p48379km6a85h4zp6kr86brh32q-audit-tmpdir.sh' '/nix/store/85clx3b0xkdf58jn161iy80y5223ilbi-compress-man-pages.sh' '/nix/store/p3l1a5y7nllfyrjn2krlwgcc3z0cd3fq-make-symlinks-relative.sh' '/nix/store/5yzw0vhkyszf2d179m0qfkgxmp5wjjx4-move-docs.sh' '/nix/store/fyaryjvghbkpfnsyw97hb3lyb37s1pd6-move-lib64.sh' '/nix/store/kd4xwxjpjxi71jkm6ka0np72if9rm3y0-move-sbin.sh' '/nix/store/pag6l61paj1dc9sv15l7bm5c17xn5kyk-move-systemd-user-units.sh' '/nix/store/cmzya9irvxzlkh7lfy6i82gbp0saxqj3-multiple-outputs.sh' '/nix/store/x8c40nfigps493a07sdr2pm5s9j1cdc0-patch-shebangs.sh' '/nix/store/cickvswrvann041nqxb0rxilc46svw1n-prune-libtool-files.sh' '/nix/store/xyff06pkhki3qy1ls77w10s0v79c9il0-reproducible-builds.sh' '/nix/store/z7k98578dfzi6l3hsvbivzm7hfqlk0zc-set-source-date-epoch-to-latest.sh' '/nix/store/pilsssjjdxvdphlg2h19p0bfx5q0jzkn-strip.sh' '/nix/store/xvvipinc7wngr82akl5i61bxdh10rajw-gcc-wrapper-15.2.0' '/nix/store/qfq318q1p94z4sy09bxbsgqi20qqb3xz-binutils-wrapper-2.46' )
declare -a postUnpackHooks=('_updateSourceDateEpochFromSourceRoot' )
shell='/nix/store/3a7sqxnls1sfbc9v4fkrpbh8gh0z8kra-bash-5.3p9/bin/bash'
export shell
PYTHONHASHSEED='0'
export PYTHONHASHSEED
AR='ar'
export AR
NIX_CC_WRAPPER_TARGET_HOST_aarch64_unknown_linux_gnu='1'
export NIX_CC_WRAPPER_TARGET_HOST_aarch64_unknown_linux_gnu
NIX_STORE='/nix/store'
export NIX_STORE
NM='nm'
export NM
configureFlags=''
export configureFlags
propagatedNativeBuildInputs=''
export propagatedNativeBuildInputs
defaultBuildInputs=''
depsBuildTargetPropagated=''
export depsBuildTargetPropagated
out='/nix/store/3dxqb73nvrvf6pa3hrvppnwb92ri7l00-devenv-shell-env'
export out
IN_NIX_SHELL='impure'
export IN_NIX_SHELL
declare -a preConfigureHooks=('_multioutConfig' )
outputInfo='out'
PYTHONPATH='/nix/store/n66abqva26rvcgdvvxj6p0xxp17b9n1v-sitecustomize.py'
export PYTHONPATH
_PYTHON_HOST_PLATFORM='linux-aarch64'
export _PYTHON_HOST_PLATFORM
JAVA_HOME='/nix/store/s80zvr4vw2qlcnxzmbpd71b4ay7grfrj-openjdk-21.0.10+7/lib/openjdk'
export JAVA_HOME
DETERMINISTIC_BUILD='1'
export DETERMINISTIC_BUILD
patches=''
export patches
declare -a pkgsHostHost=()
builder='/nix/store/3a7sqxnls1sfbc9v4fkrpbh8gh0z8kra-bash-5.3p9/bin/bash'
export builder
preConfigurePhases=' updateAutotoolsGnuConfigScriptsPhase'
declare -a propagatedHostDepFiles=('propagated-host-host-deps' 'propagated-build-inputs' )
PATH='/nix/store/kqh7aw6sj1b0kzfkx2fxm8ij4lkc0735-bash-interactive-5.3p9/bin:/nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env/bin:/nix/store/719v6ih5y46l6fnp3cbr3ylyw0pnx542-uv-0.11.11/bin:/nix/store/27znl24k4ah0is0n48z0za8m4l4hza8d-pyright-1.1.409/bin:/nix/store/bbx49kfdnrmv74mk89js0i49r3l1yi7l-opentofu-1.11.6/bin:/nix/store/ibbdy7d8gi2scr6kjw4y9bg9awzcad4h-terraform-ls-0.38.6/bin:/nix/store/s80zvr4vw2qlcnxzmbpd71b4ay7grfrj-openjdk-21.0.10+7/bin:/nix/store/ijajhr7gdrp53v2mdw4x94sbbjd9fxb0-jdt-language-server-1.58.0/bin:/nix/store/alsq5bbd7ich0jywypyhyca28x2dpr5n-clojure-1.12.4.1629/bin:/nix/store/zfi24gly7kw7g7ykw0qnqcjw9jwpvmhy-clojure-lsp-2025.11.28-12.47.43/bin:/nix/store/08q9n2bimkxw80cf7msajb5scpgqhjpq-ansible-lint-25.8.2/bin:/nix/store/isq8ia232pzg3d0j8jirpzg0vk2rkwqr-python3.13-ansible-core-2.20.5/bin:/nix/store/1mdldq2x95ri0vizys041q97hy1v5x17-python3.13-ansible-13.5.0/bin:/nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12/bin:/nix/store/xmfxad16dz6dvm929nbhm297vhhgdizp-python3.13-netaddr-1.3.0/bin:/nix/store/c7q33sqpzvws26y7nvfkpy5hds7yha78-python3.13-jsonschema-4.26.0/bin:/nix/store/brpyvajffns6603982jwx1vd5kfq0plh-python3.13-jsonpath-ng-1.7.0/bin:/nix/store/ma9z3cyw12ysj1l7ha9rmdl1zvr8bs6z-python3.13-textfsm-2.1.0/bin:/nix/store/am13qlg6mvj77p1v0sgb7rkh67azqzi6-python3.13-ttp-0.10.1/bin:/nix/store/nv76d7p4v6qvrbb1qzqgyp9fhlq0fmmc-python3.13-deepdiff-8.6.2/bin:/nix/store/6216c8xaxbljnds9pg4x1zkpk3qg47yd-python3.13-charset-normalizer-3.4.4/bin:/nix/store/yk0x8pc58nfy41anf9k72shw4jhim0yy-python3.13-netmiko-4.6.0/bin:/nix/store/2vmdlzn3r7brg6nxph2712rhbsxfmmdc-python3.13-pyserial-3.5/bin:/nix/store/l1x8086qkk9pkh5afa8f862z5d975v4s-python3.13-markdown-it-py-4.0.0/bin:/nix/store/jbdvw6amh550rpdc7bi7i9ghbpj8pd78-python3.13-pygments-2.20.0/bin:/nix/store/7xm5yyjszy45k0vxb0qrnm8sjykqiar4-python3.13-tabulate-0.10.0/bin:/nix/store/r82grs16l3zc8m73yvpjdd28kbvjir01-python3.13-yangson-1.7.5/bin:/nix/store/wd2m3ciakgivi2iapjka0xs2p1viapcn-python3.13-jmespath-1.0.1/bin:/nix/store/dkki51dvfkff891fs0yng4p751dz2wfq-python3.13-black-25.1.0/bin:/nix/store/m9g8qnggdzc6pjdw7w21vaywmlix2ccn-python3.13-yamllint-1.37.1/bin:/nix/store/pzlffjn6pfpjqnamc8azchvwrl9ysvk5-ansible-language-server-1.2.4/bin:/nix/store/yylmcmb481m97csjylklb4s61licv408-babashka-1.12.218/bin:/nix/store/m7a58rxv258bnix9ns2rkgniskyzkq9h-jet-0.7.27/bin:/nix/store/s1y1s3pij9bilz4z8zphjjwchkcyrr34-hcl2json-0.6.9/bin:/nix/store/y48ygqd28j468i7abfh9v5yy1rvj6qc2-awscli2-2.34.24/bin:/nix/store/137ddyf6621kbv22vrdv7rmhn75a78dh-skopeo-1.22.2/bin:/nix/store/bwn8vhycr7xkf9bkf2l980sxw44gid3p-hcloud-1.64.1/bin:/nix/store/dspm0p0k5lvl5q8pyn61n12vwnvdq8ip-doctl-1.157.0/bin:/nix/store/kgz9d6ywgjb2wswd1bw5in3ffzs3mdqw-pkg-config-wrapper-0.29.2/bin:/nix/store/0nhm91rg41gx04lzck4mv24wwhfw7yb4-patchelf-0.15.2/bin:/nix/store/xvvipinc7wngr82akl5i61bxdh10rajw-gcc-wrapper-15.2.0/bin:/nix/store/r1y7h8ln5hdlrwzcyysih7vxqnwralj1-gcc-15.2.0/bin:/nix/store/yiidqakcb5f0s16lr25ba3xgics4zxqq-glibc-2.42-61-bin/bin:/nix/store/xs45f7342j015kywha1dc0asbvvw1xfw-coreutils-9.10/bin:/nix/store/qfq318q1p94z4sy09bxbsgqi20qqb3xz-binutils-wrapper-2.46/bin:/nix/store/lp42hn5ax7xf2j5vlyfggjrj9hdmddpi-binutils-2.46/bin:/nix/store/xs45f7342j015kywha1dc0asbvvw1xfw-coreutils-9.10/bin:/nix/store/2vawi33dp0n3rr6zhyzh1mva54gzb50y-findutils-4.10.0/bin:/nix/store/6blh5rg9vf5ksyc630lz3r10245yba9r-diffutils-3.12/bin:/nix/store/v5bw9inlszgv4wgas7009fia4l4n0dwv-gnused-4.9/bin:/nix/store/g7mn7yzwavbs72dv6mcganlkqjj1bq9w-gnugrep-3.12/bin:/nix/store/28gh3qmd0c8bh2dnsrb33yhmfdcp6r4f-gawk-5.4.0/bin:/nix/store/fc9dnjlb0h01x20mp2ipgjgxn1avsgfr-gnutar-1.35/bin:/nix/store/wnz7kvpf0sz497dna9p6pnbln32hz7k1-gzip-1.14/bin:/nix/store/6fjzn2yb9k7ygnr607hwksllxqcv8g8q-bzip2-1.0.8-bin/bin:/nix/store/azl0q7zqafk80vk5l5i2pirzn78pr2v3-gnumake-4.4.1/bin:/nix/store/3a7sqxnls1sfbc9v4fkrpbh8gh0z8kra-bash-5.3p9/bin:/nix/store/x2rjwldm3gvv3frdkxv49bcx9c6paxrv-patch-2.8/bin:/nix/store/5fy623g2nx25nvaivq32d6fiw40x7y46-xz-5.8.3-bin/bin:/nix/store/jk0368qgqg2argpw6hshhy3dcbbv3hkl-file-5.45/bin'
export PATH
declare -a envBuildTargetHooks=('sysconfigdataHook' )
OBJDUMP='objdump'
export OBJDUMP
initialPath='/nix/store/xs45f7342j015kywha1dc0asbvvw1xfw-coreutils-9.10 /nix/store/2vawi33dp0n3rr6zhyzh1mva54gzb50y-findutils-4.10.0 /nix/store/6blh5rg9vf5ksyc630lz3r10245yba9r-diffutils-3.12 /nix/store/v5bw9inlszgv4wgas7009fia4l4n0dwv-gnused-4.9 /nix/store/g7mn7yzwavbs72dv6mcganlkqjj1bq9w-gnugrep-3.12 /nix/store/28gh3qmd0c8bh2dnsrb33yhmfdcp6r4f-gawk-5.4.0 /nix/store/fc9dnjlb0h01x20mp2ipgjgxn1avsgfr-gnutar-1.35 /nix/store/wnz7kvpf0sz497dna9p6pnbln32hz7k1-gzip-1.14 /nix/store/6fjzn2yb9k7ygnr607hwksllxqcv8g8q-bzip2-1.0.8-bin /nix/store/azl0q7zqafk80vk5l5i2pirzn78pr2v3-gnumake-4.4.1 /nix/store/3a7sqxnls1sfbc9v4fkrpbh8gh0z8kra-bash-5.3p9 /nix/store/x2rjwldm3gvv3frdkxv49bcx9c6paxrv-patch-2.8 /nix/store/5fy623g2nx25nvaivq32d6fiw40x7y46-xz-5.8.3-bin /nix/store/jk0368qgqg2argpw6hshhy3dcbbv3hkl-file-5.45'
DEVENV_STATE='/home/ubuntu/code/bigconfig/once/python/.devenv/state'
export DEVENV_STATE
CXX='g++'
export CXX
NIX_LDFLAGS='-rpath /nix/store/3dxqb73nvrvf6pa3hrvppnwb92ri7l00-devenv-shell-env/lib  -L/nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env/lib -L/nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12/lib -L/nix/store/f6ngrk2nkc04xf4vw5awdh5lrw3iq5h0-libssh-0.12.0/lib -L/nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env/lib -L/nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12/lib -L/nix/store/f6ngrk2nkc04xf4vw5awdh5lrw3iq5h0-libssh-0.12.0/lib'
export NIX_LDFLAGS
system='aarch64-linux'
export system
buildInputs=''
export buildInputs
XDG_DATA_DIRS='/nix/store/kqh7aw6sj1b0kzfkx2fxm8ij4lkc0735-bash-interactive-5.3p9/share:/nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env/share:/nix/store/719v6ih5y46l6fnp3cbr3ylyw0pnx542-uv-0.11.11/share:/nix/store/bbx49kfdnrmv74mk89js0i49r3l1yi7l-opentofu-1.11.6/share:/nix/store/s80zvr4vw2qlcnxzmbpd71b4ay7grfrj-openjdk-21.0.10+7/share:/nix/store/ijajhr7gdrp53v2mdw4x94sbbjd9fxb0-jdt-language-server-1.58.0/share:/nix/store/alsq5bbd7ich0jywypyhyca28x2dpr5n-clojure-1.12.4.1629/share:/nix/store/isq8ia232pzg3d0j8jirpzg0vk2rkwqr-python3.13-ansible-core-2.20.5/share:/nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12/share:/nix/store/yylmcmb481m97csjylklb4s61licv408-babashka-1.12.218/share:/nix/store/y48ygqd28j468i7abfh9v5yy1rvj6qc2-awscli2-2.34.24/share:/nix/store/137ddyf6621kbv22vrdv7rmhn75a78dh-skopeo-1.22.2/share:/nix/store/bwn8vhycr7xkf9bkf2l980sxw44gid3p-hcloud-1.64.1/share:/nix/store/dspm0p0k5lvl5q8pyn61n12vwnvdq8ip-doctl-1.157.0/share:/nix/store/kgz9d6ywgjb2wswd1bw5in3ffzs3mdqw-pkg-config-wrapper-0.29.2/share:/nix/store/0nhm91rg41gx04lzck4mv24wwhfw7yb4-patchelf-0.15.2/share'
export XDG_DATA_DIRS
_PYTHON_SYSCONFIGDATA_NAME='_sysconfigdata__linux_aarch64-linux-gnu'
export _PYTHON_SYSCONFIGDATA_NAME
OLDPWD=''
export OLDPWD
CLASSPATH=''
export CLASSPATH
READELF='readelf'
export READELF
propagatedBuildInputs=''
export propagatedBuildInputs
depsHostHostPropagated=''
export depsHostHostPropagated
outputDoc='out'
declare -a envHostTargetHooks=('addPkgToClassPath' 'pkgConfigWrapper_addPkgConfigPath' 'ccWrapper_addCVars' 'bintoolsWrapper_addLDVars' )
NIX_CC='/nix/store/xvvipinc7wngr82akl5i61bxdh10rajw-gcc-wrapper-15.2.0'
export NIX_CC
outputDevman='out'
LINENO='79'
NIX_PYTHONPATH='/nix/store/64ajws1as10kx24jrzs4kjqjlchy9pim-devenv-profile/lib/python3.13/site-packages'
export NIX_PYTHONPATH
DEVENV_RUNTIME='/run/user/1001/devenv-0ba226c'
export DEVENV_RUNTIME
HOST_PATH='/nix/store/xs45f7342j015kywha1dc0asbvvw1xfw-coreutils-9.10/bin:/nix/store/2vawi33dp0n3rr6zhyzh1mva54gzb50y-findutils-4.10.0/bin:/nix/store/6blh5rg9vf5ksyc630lz3r10245yba9r-diffutils-3.12/bin:/nix/store/v5bw9inlszgv4wgas7009fia4l4n0dwv-gnused-4.9/bin:/nix/store/g7mn7yzwavbs72dv6mcganlkqjj1bq9w-gnugrep-3.12/bin:/nix/store/28gh3qmd0c8bh2dnsrb33yhmfdcp6r4f-gawk-5.4.0/bin:/nix/store/fc9dnjlb0h01x20mp2ipgjgxn1avsgfr-gnutar-1.35/bin:/nix/store/wnz7kvpf0sz497dna9p6pnbln32hz7k1-gzip-1.14/bin:/nix/store/6fjzn2yb9k7ygnr607hwksllxqcv8g8q-bzip2-1.0.8-bin/bin:/nix/store/azl0q7zqafk80vk5l5i2pirzn78pr2v3-gnumake-4.4.1/bin:/nix/store/3a7sqxnls1sfbc9v4fkrpbh8gh0z8kra-bash-5.3p9/bin:/nix/store/x2rjwldm3gvv3frdkxv49bcx9c6paxrv-patch-2.8/bin:/nix/store/5fy623g2nx25nvaivq32d6fiw40x7y46-xz-5.8.3-bin/bin:/nix/store/jk0368qgqg2argpw6hshhy3dcbbv3hkl-file-5.45/bin'
export HOST_PATH
DEVENV_ROOT='/home/ubuntu/code/bigconfig/once/python'
export DEVENV_ROOT
cmakeFlags=''
export cmakeFlags
declare -a envTargetTargetHooks=()
pkg='/nix/store/xvvipinc7wngr82akl5i61bxdh10rajw-gcc-wrapper-15.2.0'
declare -a pkgsTargetTarget=()
dontAddPythonPath='1'
export dontAddPythonPath
PKG_CONFIG_PATH='/nix/store/af91fyhrr83yjmv0hvmg8qwllw3qk5mg-bash-interactive-5.3p9-dev/lib/pkgconfig:/nix/store/gr27vh188w60kqkj54hzd9icdmkk3gcn-python3-3.13.12-env/lib/pkgconfig:/nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12/lib/pkgconfig:/nix/store/659dw98ywa9f4w6gajp7sgbac49fgla5-libssh-0.12.0-dev/lib/pkgconfig'
export PKG_CONFIG_PATH
depsTargetTargetPropagated=''
export depsTargetTargetPropagated
declare -a fixupOutputHooks=('if [ -z "${dontPatchELF-}" ]; then patchELF "$prefix"; fi' 'if [[ -z "${noAuditTmpdir-}" && -e "$prefix" ]]; then auditTmpdir "$prefix"; fi' 'if [ -z "${dontGzipMan-}" ]; then compressManPages "$prefix"; fi' '_moveLib64' '_moveSbin' '_moveSystemdUserUnits' 'patchShebangsAuto' '_pruneLibtoolFiles' '_doStrip' )
depsBuildBuild=''
export depsBuildBuild
outputMan='out'
shellHook='


# Override temp directories that stdenv set to NIX_BUILD_TOP.
# Only reset those that still point to the Nix build dir; leave
# any user/CI-supplied value intact so child processes (e.g.
# `devenv processes wait`) compute the same runtime directory.
for var in TMP TMPDIR TEMP TEMPDIR; do
  if [ -n "${!var-}" ] && [ "${!var}" = "${NIX_BUILD_TOP-}" ]; then
    export "$var"=/tmp
  fi
done
if [ -n "${NIX_BUILD_TOP-}" ]; then
  unset NIX_BUILD_TOP
fi

# set path to locales on non-NixOS Linux hosts
if [ -z "${LOCALE_ARCHIVE-}" ]; then
  export LOCALE_ARCHIVE=/nix/store/v7f3q8w6qj8vm0lpn49wclhppfawk8m7-glibc-locales-2.42-61/lib/locale/locale-archive
fi


# direnv helper
if [ ! type -p direnv &>/dev/null && -f .envrc ]; then
  echo "An .envrc file was detected, but the direnv command is not installed."
  echo "To use this configuration, please install direnv: https://direnv.net/docs/installation.html"
fi

mkdir -p "$DEVENV_STATE"
if [ ! -L "$DEVENV_DOTFILE/profile" ] || [ "$(/nix/store/xs45f7342j015kywha1dc0asbvvw1xfw-coreutils-9.10/bin/readlink $DEVENV_DOTFILE/profile)" != "/nix/store/64ajws1as10kx24jrzs4kjqjlchy9pim-devenv-profile" ]
then
  ln -snf /nix/store/64ajws1as10kx24jrzs4kjqjlchy9pim-devenv-profile "$DEVENV_DOTFILE/profile"
fi
unset HOST_PATH NIX_BUILD_CORES __structuredAttrs buildInputs buildPhase builder depsBuildBuild depsBuildBuildPropagated depsBuildTarget depsBuildTargetPropagated depsHostHost depsHostHostPropagated depsTargetTarget depsTargetTargetPropagated dontAddDisableDepTrack doCheck doInstallCheck nativeBuildInputs out outputs patches phases preferLocalBuild propagatedBuildInputs propagatedNativeBuildInputs shell shellHook stdenv strictDeps

mkdir -p /run/user/1001/devenv-0ba226c
ln -snf /run/user/1001/devenv-0ba226c /home/ubuntu/code/bigconfig/once/python/.devenv/run




# Check whether the direnv integration is out of date.
{
  if [[ ":${DIRENV_ACTIVE-}:" == *":/home/ubuntu/code/bigconfig/once/python:"* ]]; then
    if [[ ! "${DEVENV_NO_DIRENVRC_OUTDATED_WARNING-}" == 1 && ! "${DEVENV_DIRENVRC_ROLLING_UPGRADE-}" == 1 ]]; then
      if [[ ${DEVENV_DIRENVRC_VERSION:-0} -lt 2 ]]; then
        direnv_line=$(grep --color=never -E "source_url.*cachix/devenv" .envrc || echo "")

        echo "✨ The direnv integration in your .envrc is out of date."
        echo ""
        echo -n "RECOMMENDED: devenv can now auto-upgrade the direnv integration. "
        if [[ -n "$direnv_line" ]]; then
          echo "To enable this feature, replace the following line in your .envrc:"
          echo ""
          echo "  $direnv_line"
          echo ""
          echo "with:"
          echo ""
          echo "  eval \"\$(devenv direnvrc)\""
        else
          echo "To enable this feature, replace the \`source_url\` line that fetches the direnvrc integration in your .envrc with:"
          echo ""
          echo "  eval \"$(devenv direnvrc)\""
        fi
        echo ""
          echo "If you prefer to continue managing the integration manually, follow the upgrade instructions at https://devenv.sh/integrations/direnv/."
          echo ""
          echo "To disable this message:"
          echo ""
          echo "  Add the following environment to your .envrc before \`use devenv\`:"
          echo ""
          echo "    export DEVENV_NO_DIRENVRC_OUTDATED_WARNING=1"
          echo ""
          echo "  Or set the following option in your devenv configuration:"
          echo ""
          echo "    devenv.warnOnNewVersion = false;"
          echo ""
      fi
    fi
  fi
} >&2

'
export shellHook
IFS=' 	
'
declare -a preFixupHooks=('_moveToShare' '_multioutDocs' '_multioutDevs' )
stdenv='/nix/store/56xv8m65vq7hd4c7y02a336xizy1js3h-stdenv-linux'
export stdenv
strictDeps=''
export strictDeps
mesonFlags=''
export mesonFlags
depsHostHost=''
export depsHostHost
preferLocalBuild='1'
export preferLocalBuild
UV_PYTHON_DOWNLOADS='never'
export UV_PYTHON_DOWNLOADS
declare -a unpackCmdHooks=('_defaultUnpack' )
declare -a envBuildHostHooks=('sysconfigdataHook' )
PKG_CONFIG='pkg-config'
export PKG_CONFIG
UV_PYTHON_PREFERENCE='only-system'
export UV_PYTHON_PREFERENCE
LD='ld'
export LD
outputDevdoc='REMOVE'
outputLib='out'
declare -a postFixupHooks=('noBrokenSymlinksInAllOutputs' '_makeSymlinksRelative' '_multioutPropagateDev' )
declare -a pkgsBuildTarget=()
prefix='/nix/store/3dxqb73nvrvf6pa3hrvppnwb92ri7l00-devenv-shell-env'
PYTHONNOUSERSITE='1'
export PYTHONNOUSERSITE
NIX_HARDENING_ENABLE='bindnow format fortify fortify3 libcxxhardeningfast pic relro stackclashprotection stackprotector strictflexarrays1 strictoverflow zerocallusedregs'
export NIX_HARDENING_ENABLE
doInstallCheck=''
export doInstallCheck
DEVENV_DOTFILE='/home/ubuntu/code/bigconfig/once/python/.devenv'
export DEVENV_DOTFILE
dontAddDisableDepTrack='1'
export dontAddDisableDepTrack
CONFIG_SHELL='/nix/store/3a7sqxnls1sfbc9v4fkrpbh8gh0z8kra-bash-5.3p9/bin/bash'
export CONFIG_SHELL
outputs='out'
export outputs
declare -a propagatedTargetDepFiles=('propagated-target-target-deps' )
outputInclude='out'
NIX_BINTOOLS='/nix/store/qfq318q1p94z4sy09bxbsgqi20qqb3xz-binutils-wrapper-2.46'
export NIX_BINTOOLS
UV_PROJECT_ENVIRONMENT='/home/ubuntu/code/bigconfig/once/python/.devenv/state/venv'
export UV_PROJECT_ENVIRONMENT
hardeningDisable=''
export hardeningDisable
depsTargetTarget=''
export depsTargetTarget
__structuredAttrs=''
export __structuredAttrs
doCheck=''
export doCheck
NIX_ENFORCE_NO_NATIVE='1'
export NIX_ENFORCE_NO_NATIVE
BASH='/nix/store/3a7sqxnls1sfbc9v4fkrpbh8gh0z8kra-bash-5.3p9/bin/bash'
NIX_PKG_CONFIG_WRAPPER_TARGET_HOST_aarch64_unknown_linux_gnu='1'
export NIX_PKG_CONFIG_WRAPPER_TARGET_HOST_aarch64_unknown_linux_gnu
outputBin='out'
SOURCE_DATE_EPOCH='315532800'
export SOURCE_DATE_EPOCH
STRIP='strip'
export STRIP
unpackFile ()
{
 
    curSrc="$1";
    echo "unpacking source archive $curSrc";
    if ! runOneHook unpackCmd "$curSrc"; then
        echo "do not know how to unpack source archive $curSrc";
        exit 1;
    fi
}
bintoolsWrapper_addLDVars ()
{
 
    local role_post;
    getHostRoleEnvHook;
    if [[ -d "$1/lib64" && ! -L "$1/lib64" ]]; then
        export NIX_LDFLAGS${role_post}+=" -L$1/lib64";
    fi;
    if [[ -d "$1/lib" ]]; then
        local -a glob=($1/lib/lib*);
        if [ "${#glob[*]}" -gt 0 ]; then
            export NIX_LDFLAGS${role_post}+=" -L$1/lib";
        fi;
    fi
}
stripHash ()
{
 
    local strippedName casematchOpt=0;
    strippedName="$(basename -- "$1")";
    shopt -q nocasematch && casematchOpt=1;
    shopt -u nocasematch;
    if [[ "$strippedName" =~ ^[a-z0-9]{32}- ]]; then
        echo "${strippedName:33}";
    else
        echo "$strippedName";
    fi;
    if (( casematchOpt )); then
        shopt -s nocasematch;
    fi
}
ccWrapper_addCVars ()
{
 
    local role_post;
    getHostRoleEnvHook;
    local found=;
    if [ -d "$1/include" ]; then
        export NIX_CFLAGS_COMPILE${role_post}+=" -isystem $1/include";
        found=1;
    fi;
    if [ -d "$1/Library/Frameworks" ]; then
        export NIX_CFLAGS_COMPILE${role_post}+=" -iframework $1/Library/Frameworks";
        found=1;
    fi;
    if [[ -n "" && -n ${NIX_STORE:-} && -n $found ]]; then
        local scrubbed="$NIX_STORE/eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee-${1#"$NIX_STORE"/*-}";
        export NIX_CFLAGS_COMPILE${role_post}+=" -fmacro-prefix-map=$1=$scrubbed";
    fi
}
showPhaseFooter ()
{
 
    local phase="$1";
    local startTime="$2";
    local endTime="$3";
    local delta=$(( endTime - startTime ));
    (( delta < 30 )) && return;
    local H=$((delta/3600));
    local M=$((delta%3600/60));
    local S=$((delta%60));
    echo -n "$phase completed in ";
    (( H > 0 )) && echo -n "$H hours ";
    (( M > 0 )) && echo -n "$M minutes ";
    echo "$S seconds"
}
addToSearchPathWithCustomDelimiter ()
{
 
    local delimiter="$1";
    local varName="$2";
    local dir="$3";
    if [[ -d "$dir" && "${!varName:+${delimiter}${!varName}${delimiter}}" != *"${delimiter}${dir}${delimiter}"* ]]; then
        export "${varName}=${!varName:+${!varName}${delimiter}}${dir}";
    fi
}
_pruneLibtoolFiles ()
{
 
    if [ "${dontPruneLibtoolFiles-}" ] || [ ! -e "$prefix" ]; then
        return;
    fi;
    find "$prefix" -type f -name '*.la' -exec grep -q '^# Generated by .*libtool' {} \; -exec grep -q "^old_library=''" {} \; -exec sed -i {} -e "/^dependency_libs='[^']/ c dependency_libs='' #pruned" \;
}
patchShebangsAuto ()
{
 
    if [[ -z "${dontPatchShebangs-}" && -e "$prefix" ]]; then
        if [[ "$output" != out && "$output" = "$outputDev" ]]; then
            patchShebangs --build "$prefix";
        else
            patchShebangs --host "$prefix";
        fi;
    fi
}
noBrokenSymlinks ()
{
 
    local -r output="${1:?}";
    local path;
    local pathParent;
    local symlinkTarget;
    local -i numDanglingSymlinks=0;
    local -i numReflexiveSymlinks=0;
    local -i numUnreadableSymlinks=0;
    if [[ ! -e $output ]]; then
        nixWarnLog "skipping non-existent output $output";
        return 0;
    fi;
    nixInfoLog "running on $output";
    while IFS= read -r -d '' path; do
        pathParent="$(dirname "$path")";
        if ! symlinkTarget="$(readlink "$path")"; then
            nixErrorLog "the symlink $path is unreadable";
            numUnreadableSymlinks+=1;
            continue;
        fi;
        if [[ $symlinkTarget == /* ]]; then
            nixInfoLog "symlink $path points to absolute target $symlinkTarget";
        else
            nixInfoLog "symlink $path points to relative target $symlinkTarget";
            symlinkTarget="$(realpath --no-symlinks --canonicalize-missing "$pathParent/$symlinkTarget")";
        fi;
        if [[ $symlinkTarget = "$TMPDIR"/* ]]; then
            nixErrorLog "the symlink $path points to $TMPDIR directory: $symlinkTarget";
            numDanglingSymlinks+=1;
            continue;
        fi;
        if [[ $symlinkTarget != "$NIX_STORE"/* ]]; then
            nixInfoLog "symlink $path points outside the Nix store; ignoring";
            continue;
        fi;
        if [[ $path == "$symlinkTarget" ]]; then
            nixErrorLog "the symlink $path is reflexive";
            numReflexiveSymlinks+=1;
        else
            if [[ ! -e $symlinkTarget ]]; then
                nixErrorLog "the symlink $path points to a missing target: $symlinkTarget";
                numDanglingSymlinks+=1;
            else
                nixDebugLog "the symlink $path is irreflexive and points to a target which exists";
            fi;
        fi;
    done < <(find "$output" -type l -print0);
    if ((numDanglingSymlinks > 0 || numReflexiveSymlinks > 0 || numUnreadableSymlinks > 0)); then
        nixErrorLog "found $numDanglingSymlinks dangling symlinks, $numReflexiveSymlinks reflexive symlinks and $numUnreadableSymlinks unreadable symlinks";
        exit 1;
    fi;
    return 0
}
appendToVar ()
{
 
    local -n nameref="$1";
    local useArray type;
    if [ -n "$__structuredAttrs" ]; then
        useArray=true;
    else
        useArray=false;
    fi;
    if type=$(declare -p "$1" 2> /dev/null); then
        case "${type#* }" in 
            -A*)
                echo "appendToVar(): ERROR: trying to use appendToVar on an associative array, use variable+=([\"X\"]=\"Y\") instead." 1>&2;
                return 1
            ;;
            -a*)
                useArray=true
            ;;
            *)
                useArray=false
            ;;
        esac;
    fi;
    shift;
    if $useArray; then
        nameref=(${nameref+"${nameref[@]}"} "$@");
    else
        nameref="${nameref-} $*";
    fi
}
substituteAllInPlace ()
{
 
    local fileName="$1";
    shift;
    substituteAll "$fileName" "$fileName" "$@"
}
moveToOutput ()
{
 
    local patt="$1";
    local dstOut="$2";
    local output;
    for output in $(getAllOutputNames);
    do
        if [ "${!output}" = "$dstOut" ]; then
            continue;
        fi;
        local srcPath;
        for srcPath in "${!output}"/$patt;
        do
            if [ ! -e "$srcPath" ] && [ ! -L "$srcPath" ]; then
                continue;
            fi;
            if [ "$dstOut" = REMOVE ]; then
                echo "Removing $srcPath";
                rm -r "$srcPath";
            else
                local dstPath="$dstOut${srcPath#${!output}}";
                echo "Moving $srcPath to $dstPath";
                if [ -d "$dstPath" ] && [ -d "$srcPath" ]; then
                    rmdir "$srcPath" --ignore-fail-on-non-empty;
                    if [ -d "$srcPath" ]; then
                        mv -t "$dstPath" "$srcPath"/*;
                        rmdir "$srcPath";
                    fi;
                else
                    mkdir -p "$(readlink -m "$dstPath/..")";
                    mv "$srcPath" "$dstPath";
                fi;
            fi;
            local srcParent="$(readlink -m "$srcPath/..")";
            if [ -n "$(find "$srcParent" -maxdepth 0 -type d -empty 2> /dev/null)" ]; then
                echo "Removing empty $srcParent/ and (possibly) its parents";
                rmdir -p --ignore-fail-on-non-empty "$srcParent" 2> /dev/null || true;
            fi;
        done;
    done
}
sysconfigdataHook ()
{
 
    if [ "$1" = '/nix/store/36mns0lq3c2zgky08rsq0xvamfzglzvj-python3-3.13.12' ]; then
        export _PYTHON_HOST_PLATFORM='linux-aarch64';
        export _PYTHON_SYSCONFIGDATA_NAME='_sysconfigdata__linux_aarch64-linux-gnu';
    fi
}
updateAutotoolsGnuConfigScriptsPhase ()
{
 
    if [ -n "${dontUpdateAutotoolsGnuConfigScripts-}" ]; then
        return;
    fi;
    for script in config.sub config.guess;
    do
        for f in $(find . -type f -name "$script");
        do
            echo "Updating Autotools / GNU config script to a newer upstream version: $f";
            cp -f "/nix/store/mcmc10r56q882xsrfqwhcvcy57r6xqgs-gnu-config-2024-01-01/$script" "$f";
        done;
    done
}
printWords ()
{
 
    (( "$#" > 0 )) || return 0;
    printf '%s ' "$@"
}
_overrideFirst ()
{
 
    if [ -z "${!1-}" ]; then
        _assignFirst "$@";
    fi
}
getTargetRole ()
{
 
    getRole "$targetOffset"
}
exitHandler ()
{
 
    exitCode="$?";
    set +e;
    if [ -n "${showBuildStats:-}" ]; then
        read -r -d '' -a buildTimes < <(times);
        echo "build times:";
        echo "user time for the shell             ${buildTimes[0]}";
        echo "system time for the shell           ${buildTimes[1]}";
        echo "user time for all child processes   ${buildTimes[2]}";
        echo "system time for all child processes ${buildTimes[3]}";
    fi;
    if (( "$exitCode" != 0 )); then
        runHook failureHook;
        if [ -n "${succeedOnFailure:-}" ]; then
            echo "build failed with exit code $exitCode (ignored)";
            mkdir -p "$out/nix-support";
            printf "%s" "$exitCode" > "$out/nix-support/failed";
            exit 0;
        fi;
    else
        runHook exitHook;
    fi;
    return "$exitCode"
}
_addToEnv ()
{
 
    local depHostOffset depTargetOffset;
    local pkg;
    for depHostOffset in "${allPlatOffsets[@]}";
    do
        local hookVar="${pkgHookVarVars[depHostOffset + 1]}";
        local pkgsVar="${pkgAccumVarVars[depHostOffset + 1]}";
        for depTargetOffset in "${allPlatOffsets[@]}";
        do
            (( depHostOffset <= depTargetOffset )) || continue;
            local hookRef="${hookVar}[$depTargetOffset - $depHostOffset]";
            if [[ -z "${strictDeps-}" ]]; then
                local visitedPkgs="";
                for pkg in "${pkgsBuildBuild[@]}" "${pkgsBuildHost[@]}" "${pkgsBuildTarget[@]}" "${pkgsHostHost[@]}" "${pkgsHostTarget[@]}" "${pkgsTargetTarget[@]}";
                do
                    if [[ "$visitedPkgs" = *"$pkg"* ]]; then
                        continue;
                    fi;
                    runHook "${!hookRef}" "$pkg";
                    visitedPkgs+=" $pkg";
                done;
            else
                local pkgsRef="${pkgsVar}[$depTargetOffset - $depHostOffset]";
                local pkgsSlice="${!pkgsRef}[@]";
                for pkg in ${!pkgsSlice+"${!pkgsSlice}"};
                do
                    runHook "${!hookRef}" "$pkg";
                done;
            fi;
        done;
    done
}
_makeSymlinksRelative ()
{
 
    local prefixes;
    prefixes=();
    for output in $(getAllOutputNames);
    do
        [ ! -e "${!output}" ] && continue;
        prefixes+=("${!output}");
    done;
    find "${prefixes[@]}" -type l -printf '%H\0%p\0' | xargs -0 -n2 -r -P "$NIX_BUILD_CORES" sh -c '
      output="$1"
      link="$2"

      linkTarget=$(readlink "$link")

      # only touch links that point inside the same output tree
      [[ $linkTarget == "$output"/* ]] || exit 0

      if [ ! -e "$linkTarget" ]; then
        echo "the symlink $link is broken, it points to $linkTarget (which is missing)"
      fi

      echo "making symlink relative: $link"
      ln -snrf "$linkTarget" "$link"
    ' _
}
_activatePkgs ()
{
 
    local hostOffset targetOffset;
    local pkg;
    for hostOffset in "${allPlatOffsets[@]}";
    do
        local pkgsVar="${pkgAccumVarVars[hostOffset + 1]}";
        for targetOffset in "${allPlatOffsets[@]}";
        do
            (( hostOffset <= targetOffset )) || continue;
            local pkgsRef="${pkgsVar}[$targetOffset - $hostOffset]";
            local pkgsSlice="${!pkgsRef}[@]";
            for pkg in ${!pkgsSlice+"${!pkgsSlice}"};
            do
                activatePackage "$pkg" "$hostOffset" "$targetOffset";
            done;
        done;
    done
}
runPhase ()
{
 
    local curPhase="$*";
    if [[ "$curPhase" = unpackPhase && -n "${dontUnpack:-}" ]]; then
        return;
    fi;
    if [[ "$curPhase" = patchPhase && -n "${dontPatch:-}" ]]; then
        return;
    fi;
    if [[ "$curPhase" = configurePhase && -n "${dontConfigure:-}" ]]; then
        return;
    fi;
    if [[ "$curPhase" = buildPhase && -n "${dontBuild:-}" ]]; then
        return;
    fi;
    if [[ "$curPhase" = checkPhase && -z "${doCheck:-}" ]]; then
        return;
    fi;
    if [[ "$curPhase" = installPhase && -n "${dontInstall:-}" ]]; then
        return;
    fi;
    if [[ "$curPhase" = fixupPhase && -n "${dontFixup:-}" ]]; then
        return;
    fi;
    if [[ "$curPhase" = installCheckPhase && -z "${doInstallCheck:-}" ]]; then
        return;
    fi;
    if [[ "$curPhase" = distPhase && -z "${doDist:-}" ]]; then
        return;
    fi;
    showPhaseHeader "$curPhase";
    dumpVars;
    local startTime endTime;
    startTime=$(date +"%s");
    eval "${!curPhase:-$curPhase}";
    endTime=$(date +"%s");
    showPhaseFooter "$curPhase" "$startTime" "$endTime";
    if [ "$curPhase" = unpackPhase ]; then
        [ -n "${sourceRoot:-}" ] && chmod +x -- "${sourceRoot}";
        cd -- "${sourceRoot:-.}";
    fi
}
substituteAllStream ()
{
 
    local -a args=();
    _allFlags;
    substituteStream "$1" "$2" "${args[@]}"
}
buildPhase ()
{
 
    runHook preBuild;
    if [[ -z "${makeFlags-}" && -z "${makefile:-}" && ! ( -e Makefile || -e makefile || -e GNUmakefile ) ]]; then
        echo "no Makefile or custom buildPhase, doing nothing";
    else
        foundMakefile=1;
        local flagsArray=(${enableParallelBuilding:+-j${NIX_BUILD_CORES}} SHELL="$SHELL");
        concatTo flagsArray makeFlags makeFlagsArray buildFlags buildFlagsArray;
        echoCmd 'build flags' "${flagsArray[@]}";
        make ${makefile:+-f $makefile} "${flagsArray[@]}";
        unset flagsArray;
    fi;
    runHook postBuild
}
addPkgToClassPath ()
{
 
    local jar;
    for jar in $1/share/java/*.jar;
    do
        export CLASSPATH=''${CLASSPATH-}''${CLASSPATH:+:}''${jar};
    done
}
printLines ()
{
 
    (( "$#" > 0 )) || return 0;
    printf '%s\n' "$@"
}
substituteStream ()
{
 
    local var=$1;
    local description=$2;
    shift 2;
    while (( "$#" )); do
        local replace_mode="$1";
        case "$1" in 
            --replace)
                if ! "$_substituteStream_has_warned_replace_deprecation"; then
                    echo "substituteStream() in derivation $name: WARNING: '--replace' is deprecated, use --replace-{fail,warn,quiet}. ($description)" 1>&2;
                    _substituteStream_has_warned_replace_deprecation=true;
                fi;
                replace_mode='--replace-warn'
            ;&
            --replace-quiet | --replace-warn | --replace-fail)
                pattern="$2";
                replacement="$3";
                shift 3;
                if ! [[ "${!var}" == *"$pattern"* ]]; then
                    if [ "$replace_mode" == --replace-warn ]; then
                        printf "substituteStream() in derivation $name: WARNING: pattern %q doesn't match anything in %s\n" "$pattern" "$description" 1>&2;
                    else
                        if [ "$replace_mode" == --replace-fail ]; then
                            printf "substituteStream() in derivation $name: ERROR: pattern %q doesn't match anything in %s\n" "$pattern" "$description" 1>&2;
                            return 1;
                        fi;
                    fi;
                fi;
                eval "$var"'=${'"$var"'//"$pattern"/"$replacement"}'
            ;;
            --subst-var)
                local varName="$2";
                shift 2;
                if ! [[ "$varName" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]]; then
                    echo "substituteStream() in derivation $name: ERROR: substitution variables must be valid Bash names, \"$varName\" isn't." 1>&2;
                    return 1;
                fi;
                if [ -z ${!varName+x} ]; then
                    echo "substituteStream() in derivation $name: ERROR: variable \$$varName is unset" 1>&2;
                    return 1;
                fi;
                pattern="@$varName@";
                replacement="${!varName}";
                eval "$var"'=${'"$var"'//"$pattern"/"$replacement"}'
            ;;
            --subst-var-by)
                pattern="@$2@";
                replacement="$3";
                eval "$var"'=${'"$var"'//"$pattern"/"$replacement"}';
                shift 3
            ;;
            *)
                echo "substituteStream() in derivation $name: ERROR: Invalid command line argument: $1" 1>&2;
                return 1
            ;;
        esac;
    done;
    printf "%s" "${!var}"
}
findInputs ()
{
 
    local -r pkg="$1";
    local -r hostOffset="$2";
    local -r targetOffset="$3";
    (( hostOffset <= targetOffset )) || exit 1;
    local varVar="${pkgAccumVarVars[hostOffset + 1]}";
    local varRef="$varVar[$((targetOffset - hostOffset))]";
    local var="${!varRef}";
    unset -v varVar varRef;
    local varSlice="$var[*]";
    case " ${!varSlice-} " in 
        *" $pkg "*)
            return 0
        ;;
    esac;
    unset -v varSlice;
    eval "$var"'+=("$pkg")';
    if ! [ -e "$pkg" ]; then
        echo "build input $pkg does not exist" 1>&2;
        exit 1;
    fi;
    function mapOffset () 
    { 
        local -r inputOffset="$1";
        local -n outputOffset="$2";
        if (( inputOffset <= 0 )); then
            outputOffset=$((inputOffset + hostOffset));
        else
            outputOffset=$((inputOffset - 1 + targetOffset));
        fi
    };
    local relHostOffset;
    for relHostOffset in "${allPlatOffsets[@]}";
    do
        local files="${propagatedDepFilesVars[relHostOffset + 1]}";
        local hostOffsetNext;
        mapOffset "$relHostOffset" hostOffsetNext;
        (( -1 <= hostOffsetNext && hostOffsetNext <= 1 )) || continue;
        local relTargetOffset;
        for relTargetOffset in "${allPlatOffsets[@]}";
        do
            (( "$relHostOffset" <= "$relTargetOffset" )) || continue;
            local fileRef="${files}[$relTargetOffset - $relHostOffset]";
            local file="${!fileRef}";
            unset -v fileRef;
            local targetOffsetNext;
            mapOffset "$relTargetOffset" targetOffsetNext;
            (( -1 <= hostOffsetNext && hostOffsetNext <= 1 )) || continue;
            [[ -f "$pkg/nix-support/$file" ]] || continue;
            local pkgNext;
            read -r -d '' pkgNext < "$pkg/nix-support/$file" || true;
            for pkgNext in $pkgNext;
            do
                findInputs "$pkgNext" "$hostOffsetNext" "$targetOffsetNext";
            done;
        done;
    done
}
_allFlags ()
{
 
    export system pname name version;
    while IFS='' read -r varName; do
        nixTalkativeLog "@${varName}@ -> ${!varName}";
        args+=("--subst-var" "$varName");
    done < <(awk 'BEGIN { for (v in ENVIRON) if (v ~ /^[a-z][a-zA-Z0-9_]*$/) print v }')
}
activatePackage ()
{
 
    local pkg="$1";
    local -r hostOffset="$2";
    local -r targetOffset="$3";
    (( hostOffset <= targetOffset )) || exit 1;
    if [ -f "$pkg" ]; then
        nixTalkativeLog "sourcing setup hook '$pkg'";
        source "$pkg";
    fi;
    if [[ -z "${strictDeps-}" || "$hostOffset" -le -1 ]]; then
        addToSearchPath _PATH "$pkg/bin";
    fi;
    if (( hostOffset <= -1 )); then
        addToSearchPath _XDG_DATA_DIRS "$pkg/share";
    fi;
    if [[ "$hostOffset" -eq 0 && -d "$pkg/bin" ]]; then
        addToSearchPath _HOST_PATH "$pkg/bin";
    fi;
    if [[ -f "$pkg/nix-support/setup-hook" ]]; then
        nixTalkativeLog "sourcing setup hook '$pkg/nix-support/setup-hook'";
        source "$pkg/nix-support/setup-hook";
    fi
}
genericBuild ()
{
 
    export GZIP_NO_TIMESTAMPS=1;
    if [ -f "${buildCommandPath:-}" ]; then
        source "$buildCommandPath";
        return;
    fi;
    if [ -n "${buildCommand:-}" ]; then
        eval "$buildCommand";
        return;
    fi;
    definePhases;
    for curPhase in ${phases[*]};
    do
        runPhase "$curPhase";
    done
}
nixInfoLog ()
{
 
    _nixLogWithLevel 3 "$*"
}
configurePhase ()
{
 
    runHook preConfigure;
    : "${configureScript=}";
    if [[ -z "$configureScript" && -x ./configure ]]; then
        configureScript=./configure;
    fi;
    if [ -z "${dontFixLibtool:-}" ]; then
        export lt_cv_deplibs_check_method="${lt_cv_deplibs_check_method-pass_all}";
        local i;
        find . -iname "ltmain.sh" -print0 | while IFS='' read -r -d '' i; do
            echo "fixing libtool script $i";
            fixLibtool "$i";
        done;
        CONFIGURE_MTIME_REFERENCE=$(mktemp configure.mtime.reference.XXXXXX);
        find . -executable -type f -name configure -exec grep -l 'GNU Libtool is free software; you can redistribute it and/or modify' {} \; -exec touch -r {} "$CONFIGURE_MTIME_REFERENCE" \; -exec sed -i s_/usr/bin/file_file_g {} \; -exec touch -r "$CONFIGURE_MTIME_REFERENCE" {} \;;
        rm -f "$CONFIGURE_MTIME_REFERENCE";
    fi;
    if [[ -z "${dontAddPrefix:-}" && -n "$prefix" ]]; then
        local -r prefixKeyOrDefault="${prefixKey:---prefix=}";
        if [ "${prefixKeyOrDefault: -1}" = " " ]; then
            prependToVar configureFlags "$prefix";
            prependToVar configureFlags "${prefixKeyOrDefault::-1}";
        else
            prependToVar configureFlags "$prefixKeyOrDefault$prefix";
        fi;
    fi;
    if [[ -f "$configureScript" ]]; then
        if [ -z "${dontAddDisableDepTrack:-}" ]; then
            if grep -q dependency-tracking "$configureScript"; then
                prependToVar configureFlags --disable-dependency-tracking;
            fi;
        fi;
        if [ -z "${dontDisableStatic:-}" ]; then
            if grep -q enable-static "$configureScript"; then
                prependToVar configureFlags --disable-static;
            fi;
        fi;
        if [ -z "${dontPatchShebangsInConfigure:-}" ]; then
            patchShebangs --build "$configureScript";
        fi;
    fi;
    if [ -n "$configureScript" ]; then
        local -a flagsArray;
        concatTo flagsArray configureFlags configureFlagsArray;
        echoCmd 'configure flags' "${flagsArray[@]}";
        $configureScript "${flagsArray[@]}";
        unset flagsArray;
    else
        echo "no configure script, doing nothing";
    fi;
    runHook postConfigure
}
getTargetRoleEnvHook ()
{
 
    getRole "$depTargetOffset"
}
concatStringsSep ()
{
 
    local sep="$1";
    local name="$2";
    local type oldifs;
    if type=$(declare -p "$name" 2> /dev/null); then
        local -n nameref="$name";
        case "${type#* }" in 
            -A*)
                echo "concatStringsSep(): ERROR: trying to use concatStringsSep on an associative array." 1>&2;
                return 1
            ;;
            -a*)
                local IFS="$(printf '\036')"
            ;;
            *)
                local IFS=" "
            ;;
        esac;
        local ifs_separated="${nameref[*]}";
        echo -n "${ifs_separated//"$IFS"/"$sep"}";
    fi
}
runHook ()
{
 
    local hookName="$1";
    shift;
    local hooksSlice="${hookName%Hook}Hooks[@]";
    local hook;
    for hook in "_callImplicitHook 0 $hookName" ${!hooksSlice+"${!hooksSlice}"};
    do
        _logHook "$hookName" "$hook" "$@";
        _eval "$hook" "$@";
    done;
    return 0
}
nixVomitLog ()
{
 
    _nixLogWithLevel 7 "$*"
}
_moveToShare ()
{
 
    if [ -n "$__structuredAttrs" ]; then
        if [ -z "${forceShare-}" ]; then
            forceShare=(man doc info);
        fi;
    else
        forceShare=(${forceShare:-man doc info});
    fi;
    if [[ -z "$out" ]]; then
        return;
    fi;
    for d in "${forceShare[@]}";
    do
        if [ -d "$out/$d" ]; then
            if [ -d "$out/share/$d" ]; then
                echo "both $d/ and share/$d/ exist!";
            else
                echo "moving $out/$d to $out/share/$d";
                mkdir -p $out/share;
                mv $out/$d $out/share/;
            fi;
        fi;
    done
}
installCheckPhase ()
{
 
    runHook preInstallCheck;
    if [[ -z "${foundMakefile:-}" ]]; then
        echo "no Makefile or custom installCheckPhase, doing nothing";
    else
        if [[ -z "${installCheckTarget:-}" ]] && ! make -n ${makefile:+-f $makefile} "${installCheckTarget:-installcheck}" > /dev/null 2>&1; then
            echo "no installcheck target in ${makefile:-Makefile}, doing nothing";
        else
            local flagsArray=(${enableParallelChecking:+-j${NIX_BUILD_CORES}} SHELL="$SHELL");
            concatTo flagsArray makeFlags makeFlagsArray installCheckFlags installCheckFlagsArray installCheckTarget=installcheck;
            echoCmd 'installcheck flags' "${flagsArray[@]}";
            make ${makefile:+-f $makefile} "${flagsArray[@]}";
            unset flagsArray;
        fi;
    fi;
    runHook postInstallCheck
}
isScript ()
{
 
    local fn="$1";
    local fd;
    local magic;
    exec {fd}< "$fn";
    LANG=C read -r -n 2 -u "$fd" magic;
    exec {fd}>&-;
    if [[ "$magic" =~ \#! ]]; then
        return 0;
    else
        return 1;
    fi
}
nixWarnLog ()
{
 
    _nixLogWithLevel 1 "$*"
}
isELF ()
{
 
    local fn="$1";
    local fd;
    local magic;
    exec {fd}< "$fn";
    LANG=C read -r -n 4 -u "$fd" magic;
    exec {fd}>&-;
    if [ "$magic" = 'ELF' ]; then
        return 0;
    else
        return 1;
    fi
}
getHostRole ()
{
 
    getRole "$hostOffset"
}
addEnvHooks ()
{
 
    local depHostOffset="$1";
    shift;
    local pkgHookVarsSlice="${pkgHookVarVars[$depHostOffset + 1]}[@]";
    local pkgHookVar;
    for pkgHookVar in "${!pkgHookVarsSlice}";
    do
        eval "${pkgHookVar}s"'+=("$@")';
    done
}
prependToVar ()
{
 
    local -n nameref="$1";
    local useArray type;
    if [ -n "$__structuredAttrs" ]; then
        useArray=true;
    else
        useArray=false;
    fi;
    if type=$(declare -p "$1" 2> /dev/null); then
        case "${type#* }" in 
            -A*)
                echo "prependToVar(): ERROR: trying to use prependToVar on an associative array." 1>&2;
                return 1
            ;;
            -a*)
                useArray=true
            ;;
            *)
                useArray=false
            ;;
        esac;
    fi;
    shift;
    if $useArray; then
        nameref=("$@" ${nameref+"${nameref[@]}"});
    else
        nameref="$* ${nameref-}";
    fi
}
runOneHook ()
{
 
    local hookName="$1";
    shift;
    local hooksSlice="${hookName%Hook}Hooks[@]";
    local hook ret=1;
    for hook in "_callImplicitHook 1 $hookName" ${!hooksSlice+"${!hooksSlice}"};
    do
        _logHook "$hookName" "$hook" "$@";
        if _eval "$hook" "$@"; then
            ret=0;
            break;
        fi;
    done;
    return "$ret"
}
unpackPhase ()
{
 
    runHook preUnpack;
    if [ -z "${srcs:-}" ]; then
        if [ -z "${src:-}" ]; then
            echo 'variable $src or $srcs should point to the source';
            exit 1;
        fi;
        srcs="$src";
    fi;
    local -a srcsArray;
    concatTo srcsArray srcs;
    local dirsBefore="";
    for i in *;
    do
        if [ -d "$i" ]; then
            dirsBefore="$dirsBefore $i ";
        fi;
    done;
    for i in "${srcsArray[@]}";
    do
        unpackFile "$i";
    done;
    : "${sourceRoot=}";
    if [ -n "${setSourceRoot:-}" ]; then
        runOneHook setSourceRoot;
    else
        if [ -z "$sourceRoot" ]; then
            for i in *;
            do
                if [ -d "$i" ]; then
                    case $dirsBefore in 
                        *\ $i\ *)

                        ;;
                        *)
                            if [ -n "$sourceRoot" ]; then
                                echo "unpacker produced multiple directories";
                                exit 1;
                            fi;
                            sourceRoot="$i"
                        ;;
                    esac;
                fi;
            done;
        fi;
    fi;
    if [ -z "$sourceRoot" ]; then
        echo "unpacker appears to have produced no directories";
        exit 1;
    fi;
    echo "source root is $sourceRoot";
    if [ "${dontMakeSourcesWritable:-0}" != 1 ]; then
        chmod -R u+w -- "$sourceRoot";
    fi;
    runHook postUnpack
}
compressManPages ()
{
 
    local dir="$1";
    if [ -L "$dir"/share ] || [ -L "$dir"/share/man ] || [ ! -d "$dir/share/man" ]; then
        return;
    fi;
    echo "gzipping man pages under $dir/share/man/";
    find "$dir"/share/man/ -type f -a '!' -regex '.*\.\(bz2\|gz\|xz\)$' -print0 | xargs -0 -n1 -P "$NIX_BUILD_CORES" gzip -n -f;
    find "$dir"/share/man/ -type l -a '!' -regex '.*\.\(bz2\|gz\|xz\)$' -print0 | sort -z | while IFS= read -r -d '' f; do
        local target;
        target="$(readlink -f "$f")";
        if [ -f "$target".gz ]; then
            ln -sf "$target".gz "$f".gz && rm "$f";
        fi;
    done
}
nixTalkativeLog ()
{
 
    _nixLogWithLevel 4 "$*"
}
echoCmd ()
{
 
    printf "%s:" "$1";
    shift;
    printf ' %q' "$@";
    echo
}
nixChattyLog ()
{
 
    _nixLogWithLevel 5 "$*"
}
_moveSbin ()
{
 
    if [ "${dontMoveSbin-}" = 1 ]; then
        return;
    fi;
    if [ ! -e "$prefix/sbin" -o -L "$prefix/sbin" ]; then
        return;
    fi;
    echo "moving $prefix/sbin/* to $prefix/bin";
    mkdir -p $prefix/bin;
    shopt -s dotglob;
    for i in $prefix/sbin/*;
    do
        mv "$i" $prefix/bin;
    done;
    shopt -u dotglob;
    rmdir $prefix/sbin;
    ln -s bin $prefix/sbin
}
patchPhase ()
{
 
    runHook prePatch;
    local -a patchesArray;
    concatTo patchesArray patches;
    local -a flagsArray;
    concatTo flagsArray patchFlags=-p1;
    for i in "${patchesArray[@]}";
    do
        echo "applying patch $i";
        local uncompress=cat;
        case "$i" in 
            *.gz)
                uncompress="gzip -d"
            ;;
            *.bz2)
                uncompress="bzip2 -d"
            ;;
            *.xz)
                uncompress="xz -d"
            ;;
            *.lzma)
                uncompress="lzma -d"
            ;;
        esac;
        $uncompress < "$i" 2>&1 | patch "${flagsArray[@]}";
    done;
    runHook postPatch
}
printPhases ()
{
 
    definePhases;
    local phase;
    for phase in ${phases[*]};
    do
        printf '%s\n' "$phase";
    done
}
recordPropagatedDependencies ()
{
 
    declare -ra flatVars=(depsBuildBuildPropagated propagatedNativeBuildInputs depsBuildTargetPropagated depsHostHostPropagated propagatedBuildInputs depsTargetTargetPropagated);
    declare -ra flatFiles=("${propagatedBuildDepFiles[@]}" "${propagatedHostDepFiles[@]}" "${propagatedTargetDepFiles[@]}");
    local propagatedInputsIndex;
    for propagatedInputsIndex in "${!flatVars[@]}";
    do
        local propagatedInputsSlice="${flatVars[$propagatedInputsIndex]}[@]";
        local propagatedInputsFile="${flatFiles[$propagatedInputsIndex]}";
        [[ -n "${!propagatedInputsSlice}" ]] || continue;
        mkdir -p "${!outputDev}/nix-support";
        printWords ${!propagatedInputsSlice} > "${!outputDev}/nix-support/$propagatedInputsFile";
    done
}
substituteAll ()
{
 
    local input="$1";
    local output="$2";
    local -a args=();
    _allFlags;
    substitute "$input" "$output" "${args[@]}"
}
nixLog ()
{
 
    [[ -z ${NIX_LOG_FD-} ]] && return 0;
    local callerName="${FUNCNAME[1]}";
    if [[ $callerName == "_callImplicitHook" ]]; then
        callerName="${hookName:?}";
    fi;
    printf "%s: %s\n" "$callerName" "$*" >&"$NIX_LOG_FD"
}
pkgConfigWrapper_addPkgConfigPath ()
{
 
    local role_post;
    getHostRoleEnvHook;
    addToSearchPath "PKG_CONFIG_PATH${role_post}" "$1/lib/pkgconfig";
    addToSearchPath "PKG_CONFIG_PATH${role_post}" "$1/share/pkgconfig"
}
_multioutDocs ()
{
 
    local REMOVE=REMOVE;
    moveToOutput share/info "${!outputInfo}";
    moveToOutput share/doc "${!outputDoc}";
    moveToOutput share/gtk-doc "${!outputDevdoc}";
    moveToOutput share/devhelp/books "${!outputDevdoc}";
    moveToOutput share/man "${!outputMan}";
    moveToOutput share/man/man3 "${!outputDevman}"
}
substituteInPlace ()
{
 
    local -a fileNames=();
    for arg in "$@";
    do
        if [[ "$arg" = "--"* ]]; then
            break;
        fi;
        fileNames+=("$arg");
        shift;
    done;
    if ! [[ "${#fileNames[@]}" -gt 0 ]]; then
        echo "substituteInPlace called without any files to operate on (files must come before options!)" 1>&2;
        return 1;
    fi;
    for file in "${fileNames[@]}";
    do
        substitute "$file" "$file" "$@";
    done
}
toPythonPath ()
{
 
    local paths="$1";
    local result=;
    for i in $paths;
    do
        p="$i/lib/python3.13/site-packages";
        result="${result}${result:+:}$p";
    done;
    echo $result
}
_logHook ()
{
 
    if [[ -z ${NIX_LOG_FD-} ]]; then
        return;
    fi;
    local hookKind="$1";
    local hookExpr="$2";
    shift 2;
    if declare -F "$hookExpr" > /dev/null 2>&1; then
        nixTalkativeLog "calling '$hookKind' function hook '$hookExpr'" "$@";
    else
        if type -p "$hookExpr" > /dev/null; then
            nixTalkativeLog "sourcing '$hookKind' script hook '$hookExpr'";
        else
            if [[ "$hookExpr" != "_callImplicitHook"* ]]; then
                local exprToOutput;
                if [[ ${NIX_DEBUG:-0} -ge 5 ]]; then
                    exprToOutput="$hookExpr";
                else
                    local hookExprLine;
                    while IFS= read -r hookExprLine; do
                        hookExprLine="${hookExprLine#"${hookExprLine%%[![:space:]]*}"}";
                        if [[ -n "$hookExprLine" ]]; then
                            exprToOutput+="$hookExprLine\\n ";
                        fi;
                    done <<< "$hookExpr";
                    exprToOutput="${exprToOutput%%\\n }";
                fi;
                nixTalkativeLog "evaling '$hookKind' string hook '$exprToOutput'";
            fi;
        fi;
    fi
}
_moveSystemdUserUnits ()
{
 
    if [ "${dontMoveSystemdUserUnits:-0}" = 1 ]; then
        return;
    fi;
    if [ ! -e "${prefix:?}/lib/systemd/user" ]; then
        return;
    fi;
    local source="$prefix/lib/systemd/user";
    local target="$prefix/share/systemd/user";
    echo "moving $source/* to $target";
    mkdir -p "$target";
    ( shopt -s dotglob;
    for i in "$source"/*;
    do
        mv "$i" "$target";
    done );
    rmdir "$source";
    ln -s "$target" "$source"
}
_addRpathPrefix ()
{
 
    if [ "${NIX_NO_SELF_RPATH:-0}" != 1 ]; then
        export NIX_LDFLAGS="-rpath $1/lib ${NIX_LDFLAGS-}";
    fi
}
_multioutPropagateDev ()
{
 
    if [ "$(getAllOutputNames)" = "out" ]; then
        return;
    fi;
    local outputFirst;
    for outputFirst in $(getAllOutputNames);
    do
        break;
    done;
    local propagaterOutput="$outputDev";
    if [ -z "$propagaterOutput" ]; then
        propagaterOutput="$outputFirst";
    fi;
    if [ -z "${propagatedBuildOutputs+1}" ]; then
        local po_dirty="$outputBin $outputInclude $outputLib";
        set +o pipefail;
        propagatedBuildOutputs=`echo "$po_dirty"             | tr -s ' ' '\n' | grep -v -F "$propagaterOutput"             | sort -u | tr '\n' ' ' `;
        set -o pipefail;
    fi;
    if [ -z "$propagatedBuildOutputs" ]; then
        return;
    fi;
    mkdir -p "${!propagaterOutput}"/nix-support;
    for output in $propagatedBuildOutputs;
    do
        echo -n " ${!output}" >> "${!propagaterOutput}"/nix-support/propagated-build-inputs;
    done
}
_nixLogWithLevel ()
{
 
    [[ -z ${NIX_LOG_FD-} || ${NIX_DEBUG:-0} -lt ${1:?} ]] && return 0;
    local logLevel;
    case "${1:?}" in 
        0)
            logLevel=ERROR
        ;;
        1)
            logLevel=WARN
        ;;
        2)
            logLevel=NOTICE
        ;;
        3)
            logLevel=INFO
        ;;
        4)
            logLevel=TALKATIVE
        ;;
        5)
            logLevel=CHATTY
        ;;
        6)
            logLevel=DEBUG
        ;;
        7)
            logLevel=VOMIT
        ;;
        *)
            echo "_nixLogWithLevel: called with invalid log level: ${1:?}" >&"$NIX_LOG_FD";
            return 1
        ;;
    esac;
    local callerName="${FUNCNAME[2]}";
    if [[ $callerName == "_callImplicitHook" ]]; then
        callerName="${hookName:?}";
    fi;
    printf "%s: %s: %s\n" "$logLevel" "$callerName" "${2:?}" >&"$NIX_LOG_FD"
}
getTargetRoleWrapper ()
{
 
    case $targetOffset in 
        -1)
            export NIX_BINTOOLS_WRAPPER_TARGET_BUILD_aarch64_unknown_linux_gnu=1
        ;;
        0)
            export NIX_BINTOOLS_WRAPPER_TARGET_HOST_aarch64_unknown_linux_gnu=1
        ;;
        1)
            export NIX_BINTOOLS_WRAPPER_TARGET_TARGET_aarch64_unknown_linux_gnu=1
        ;;
        *)
            echo "binutils-wrapper-2.46: used as improper sort of dependency" 1>&2;
            return 1
        ;;
    esac
}
isMachO ()
{
 
    local fn="$1";
    local fd;
    local magic;
    exec {fd}< "$fn";
    LANG=C read -r -n 4 -u "$fd" magic;
    exec {fd}>&-;
    if [[ "$magic" = $(echo -ne "\xfe\xed\xfa\xcf") || "$magic" = $(echo -ne "\xcf\xfa\xed\xfe") ]]; then
        return 0;
    else
        if [[ "$magic" = $(echo -ne "\xfe\xed\xfa\xce") || "$magic" = $(echo -ne "\xce\xfa\xed\xfe") ]]; then
            return 0;
        else
            if [[ "$magic" = $(echo -ne "\xca\xfe\xba\xbe") || "$magic" = $(echo -ne "\xbe\xba\xfe\xca") ]]; then
                return 0;
            else
                return 1;
            fi;
        fi;
    fi
}
_eval ()
{
 
    if declare -F "$1" > /dev/null 2>&1; then
        "$@";
    else
        eval "$1";
    fi
}
distPhase ()
{
 
    runHook preDist;
    local flagsArray=();
    concatTo flagsArray distFlags distFlagsArray distTarget=dist;
    echo 'dist flags: %q' "${flagsArray[@]}";
    make ${makefile:+-f $makefile} "${flagsArray[@]}";
    if [ "${dontCopyDist:-0}" != 1 ]; then
        mkdir -p "$out/tarballs";
        cp -pvd ${tarballs[*]:-*.tar.gz} "$out/tarballs";
    fi;
    runHook postDist
}
dumpVars ()
{
 
    if [[ "${noDumpEnvVars:-0}" != 1 && -d "$NIX_BUILD_TOP" ]]; then
        local old_umask;
        old_umask=$(umask);
        umask 0077;
        export 2> /dev/null > "$NIX_BUILD_TOP/env-vars";
        umask "$old_umask";
    fi
}
fixLibtool ()
{
 
    local search_path;
    for flag in $NIX_LDFLAGS;
    do
        case $flag in 
            -L*)
                search_path+=" ${flag#-L}"
            ;;
        esac;
    done;
    sed -i "$1" -e "s^eval \(sys_lib_search_path=\).*^\1'${search_path:-}'^" -e 's^eval sys_lib_.+search_path=.*^^'
}
getHostRoleEnvHook ()
{
 
    getRole "$depHostOffset"
}
_assignFirst ()
{
 
    local varName="$1";
    local _var;
    local REMOVE=REMOVE;
    shift;
    for _var in "$@";
    do
        if [ -n "${!_var-}" ]; then
            eval "${varName}"="${_var}";
            return;
        fi;
    done;
    echo;
    echo "error: _assignFirst: could not find a non-empty variable whose name to assign to ${varName}.";
    echo "       The following variables were all unset or empty:";
    echo "           $*";
    if [ -z "${out:-}" ]; then
        echo '       If you do not want an "out" output in your derivation, make sure to define';
        echo '       the other specific required outputs. This can be achieved by picking one';
        echo "       of the above as an output.";
        echo '       You do not have to remove "out" if you want to have a different default';
        echo '       output, because the first output is taken as a default.';
        echo;
    fi;
    return 1
}
nixErrorLog ()
{
 
    _nixLogWithLevel 0 "$*"
}
_callImplicitHook ()
{
 
    local def="$1";
    local hookName="$2";
    if declare -F "$hookName" > /dev/null; then
        nixTalkativeLog "calling implicit '$hookName' function hook";
        "$hookName";
    else
        if type -p "$hookName" > /dev/null; then
            nixTalkativeLog "sourcing implicit '$hookName' script hook";
            source "$hookName";
        else
            if [ -n "${!hookName:-}" ]; then
                nixTalkativeLog "evaling implicit '$hookName' string hook";
                eval "${!hookName}";
            else
                return "$def";
            fi;
        fi;
    fi
}
consumeEntire ()
{
 
    if IFS='' read -r -d '' "$1"; then
        echo "consumeEntire(): ERROR: Input null bytes, won't process" 1>&2;
        return 1;
    fi
}
_multioutConfig ()
{
 
    if [ "$(getAllOutputNames)" = "out" ] || [ -z "${setOutputFlags-1}" ]; then
        return;
    fi;
    if [ -z "${shareDocName:-}" ]; then
        local confScript="${configureScript:-}";
        if [ -z "$confScript" ] && [ -x ./configure ]; then
            confScript=./configure;
        fi;
        if [ -f "$confScript" ]; then
            local shareDocName="$(sed -n "s/^PACKAGE_TARNAME='\(.*\)'$/\1/p" < "$confScript")";
        fi;
        if [ -z "$shareDocName" ] || echo "$shareDocName" | grep -q '[^a-zA-Z0-9_-]'; then
            shareDocName="$(echo "$name" | sed 's/-[^a-zA-Z].*//')";
        fi;
    fi;
    prependToVar configureFlags --bindir="${!outputBin}"/bin --sbindir="${!outputBin}"/sbin --includedir="${!outputInclude}"/include --mandir="${!outputMan}"/share/man --infodir="${!outputInfo}"/share/info --docdir="${!outputDoc}"/share/doc/"${shareDocName}" --libdir="${!outputLib}"/lib --libexecdir="${!outputLib}"/libexec --localedir="${!outputLib}"/share/locale;
    prependToVar installFlags pkgconfigdir="${!outputDev}"/lib/pkgconfig m4datadir="${!outputDev}"/share/aclocal aclocaldir="${!outputDev}"/share/aclocal
}
_doStrip ()
{
 
    local -ra flags=(dontStripHost dontStripTarget);
    local -ra debugDirs=(stripDebugList stripDebugListTarget);
    local -ra allDirs=(stripAllList stripAllListTarget);
    local -ra stripCmds=(STRIP STRIP_FOR_TARGET);
    local -ra ranlibCmds=(RANLIB RANLIB_FOR_TARGET);
    stripDebugList=${stripDebugList[*]:-lib lib32 lib64 libexec bin sbin Applications Library/Frameworks};
    stripDebugListTarget=${stripDebugListTarget[*]:-};
    stripAllList=${stripAllList[*]:-};
    stripAllListTarget=${stripAllListTarget[*]:-};
    local i;
    for i in ${!stripCmds[@]};
    do
        local -n flag="${flags[$i]}";
        local -n debugDirList="${debugDirs[$i]}";
        local -n allDirList="${allDirs[$i]}";
        local -n stripCmd="${stripCmds[$i]}";
        local -n ranlibCmd="${ranlibCmds[$i]}";
        if [[ -n "${dontStrip-}" || -n "${flag-}" ]] || ! type -f "${stripCmd-}" 2> /dev/null 1>&2; then
            continue;
        fi;
        stripDirs "$stripCmd" "$ranlibCmd" "$debugDirList" "${stripDebugFlags[*]:--S -p}";
        stripDirs "$stripCmd" "$ranlibCmd" "$allDirList" "${stripAllFlags[*]:--s -p}";
    done
}
definePhases ()
{
 
    if [ -z "${phases[*]:-}" ]; then
        phases="${prePhases[*]:-} unpackPhase patchPhase ${preConfigurePhases[*]:-}             configurePhase ${preBuildPhases[*]:-} buildPhase checkPhase             ${preInstallPhases[*]:-} installPhase ${preFixupPhases[*]:-} fixupPhase installCheckPhase             ${preDistPhases[*]:-} distPhase ${postPhases[*]:-}";
    fi
}
fixupPhase ()
{
 
    local output;
    for output in $(getAllOutputNames);
    do
        if [ -e "${!output}" ]; then
            chmod -R u+w,u-s,g-s "${!output}";
        fi;
    done;
    runHook preFixup;
    local output;
    for output in $(getAllOutputNames);
    do
        prefix="${!output}" runHook fixupOutput;
    done;
    recordPropagatedDependencies;
    if [ -n "${setupHook:-}" ]; then
        mkdir -p "${!outputDev}/nix-support";
        substituteAll "$setupHook" "${!outputDev}/nix-support/setup-hook";
    fi;
    if [ -n "${setupHooks:-}" ]; then
        mkdir -p "${!outputDev}/nix-support";
        local hook;
        for hook in ${setupHooks[@]};
        do
            local content;
            consumeEntire content < "$hook";
            substituteAllStream content "file '$hook'" >> "${!outputDev}/nix-support/setup-hook";
            unset -v content;
        done;
        unset -v hook;
    fi;
    if [ -n "${propagatedUserEnvPkgs[*]:-}" ]; then
        mkdir -p "${!outputBin}/nix-support";
        printWords "${propagatedUserEnvPkgs[@]}" > "${!outputBin}/nix-support/propagated-user-env-packages";
    fi;
    runHook postFixup
}
getRole ()
{
 
    case $1 in 
        -1)
            role_post='_FOR_BUILD'
        ;;
        0)
            role_post=''
        ;;
        1)
            role_post='_FOR_TARGET'
        ;;
        *)
            echo "binutils-wrapper-2.46: used as improper sort of dependency" 1>&2;
            return 1
        ;;
    esac
}
installPhase ()
{
 
    runHook preInstall;
    if [[ -z "${makeFlags-}" && -z "${makefile:-}" && ! ( -e Makefile || -e makefile || -e GNUmakefile ) ]]; then
        echo "no Makefile or custom installPhase, doing nothing";
        runHook postInstall;
        return;
    else
        foundMakefile=1;
    fi;
    if [ -n "$prefix" ]; then
        mkdir -p "$prefix";
    fi;
    local flagsArray=(${enableParallelInstalling:+-j${NIX_BUILD_CORES}} SHELL="$SHELL");
    concatTo flagsArray makeFlags makeFlagsArray installFlags installFlagsArray installTargets=install;
    echoCmd 'install flags' "${flagsArray[@]}";
    make ${makefile:+-f $makefile} "${flagsArray[@]}";
    unset flagsArray;
    runHook postInstall
}
concatTo ()
{
 
    local -;
    set -o noglob;
    local -n targetref="$1";
    shift;
    local arg default name type;
    for arg in "$@";
    do
        IFS="=" read -r name default <<< "$arg";
        local -n nameref="$name";
        if [[ -z "${nameref[*]}" && -n "$default" ]]; then
            targetref+=("$default");
        else
            if type=$(declare -p "$name" 2> /dev/null); then
                case "${type#* }" in 
                    -A*)
                        echo "concatTo(): ERROR: trying to use concatTo on an associative array." 1>&2;
                        return 1
                    ;;
                    -a*)
                        targetref+=("${nameref[@]}")
                    ;;
                    *)
                        if [[ "$name" = *"Array" ]]; then
                            nixErrorLog "concatTo(): $name is not declared as array, treating as a singleton. This will become an error in future";
                            targetref+=(${nameref+"${nameref[@]}"});
                        else
                            targetref+=(${nameref-});
                        fi
                    ;;
                esac;
            fi;
        fi;
    done
}
mapOffset ()
{
 
    local -r inputOffset="$1";
    local -n outputOffset="$2";
    if (( inputOffset <= 0 )); then
        outputOffset=$((inputOffset + hostOffset));
    else
        outputOffset=$((inputOffset - 1 + targetOffset));
    fi
}
checkPhase ()
{
 
    runHook preCheck;
    if [[ -z "${foundMakefile:-}" ]]; then
        echo "no Makefile or custom checkPhase, doing nothing";
        runHook postCheck;
        return;
    fi;
    if [[ -z "${checkTarget:-}" ]]; then
        if make -n ${makefile:+-f $makefile} check > /dev/null 2>&1; then
            checkTarget="check";
        else
            if make -n ${makefile:+-f $makefile} test > /dev/null 2>&1; then
                checkTarget="test";
            fi;
        fi;
    fi;
    if [[ -z "${checkTarget:-}" ]]; then
        echo "no check/test target in ${makefile:-Makefile}, doing nothing";
    else
        local flagsArray=(${enableParallelChecking:+-j${NIX_BUILD_CORES}} SHELL="$SHELL");
        concatTo flagsArray makeFlags makeFlagsArray checkFlags=VERBOSE=y checkFlagsArray checkTarget;
        echoCmd 'check flags' "${flagsArray[@]}";
        make ${makefile:+-f $makefile} "${flagsArray[@]}";
        unset flagsArray;
    fi;
    runHook postCheck
}
_moveLib64 ()
{
 
    if [ "${dontMoveLib64-}" = 1 ]; then
        return;
    fi;
    if [ ! -e "$prefix/lib64" -o -L "$prefix/lib64" ]; then
        return;
    fi;
    echo "moving $prefix/lib64/* to $prefix/lib";
    mkdir -p $prefix/lib;
    shopt -s dotglob;
    for i in $prefix/lib64/*;
    do
        mv --no-clobber "$i" $prefix/lib;
    done;
    shopt -u dotglob;
    rmdir $prefix/lib64;
    ln -s lib $prefix/lib64
}
nixDebugLog ()
{
 
    _nixLogWithLevel 6 "$*"
}
nixNoticeLog ()
{
 
    _nixLogWithLevel 2 "$*"
}
auditTmpdir ()
{
 
    local dir="$1";
    [ -e "$dir" ] || return 0;
    echo "checking for references to $TMPDIR/ in $dir...";
    local tmpdir elf_fifo script_fifo;
    tmpdir="$(mktemp -d)";
    elf_fifo="$tmpdir/elf";
    script_fifo="$tmpdir/script";
    mkfifo "$elf_fifo" "$script_fifo";
    ( find "$dir" -type f -not -path '*/.build-id/*' -print0 | while IFS= read -r -d '' file; do
        if isELF "$file"; then
            printf '%s\0' "$file" 1>&3;
        else
            if isScript "$file"; then
                filename=${file##*/};
                dir=${file%/*};
                if [ -e "$dir/.$filename-wrapped" ]; then
                    printf '%s\0' "$file" 1>&4;
                fi;
            fi;
        fi;
    done;
    exec 3>&- 4>&- ) 3> "$elf_fifo" 4> "$script_fifo" & ( xargs -0 -r -P "$NIX_BUILD_CORES" -n 1 sh -c '
            if { printf :; patchelf --print-rpath "$1"; } | grep -q -F ":$TMPDIR/"; then
                echo "RPATH of binary $1 contains a forbidden reference to $TMPDIR/"
                exit 1
            fi
        ' _ < "$elf_fifo" ) & local pid_elf=$!;
    local pid_script;
    ( xargs -0 -r -P "$NIX_BUILD_CORES" -n 1 sh -c '
            if grep -q -F "$TMPDIR/" "$1"; then
                echo "wrapper script $1 contains a forbidden reference to $TMPDIR/"
                exit 1
            fi
        ' _ < "$script_fifo" ) & local pid_script=$!;
    wait "$pid_elf" || { 
        echo "Some binaries contain forbidden references to $TMPDIR/. Check the error above!";
        exit 1
    };
    wait "$pid_script" || { 
        echo "Some scripts contain forbidden references to $TMPDIR/. Check the error above!";
        exit 1
    };
    rm -r "$tmpdir"
}
noBrokenSymlinksInAllOutputs ()
{
 
    if [[ -z ${dontCheckForBrokenSymlinks-} ]]; then
        for output in $(getAllOutputNames);
        do
            noBrokenSymlinks "${!output}";
        done;
    fi
}
_defaultUnpack ()
{
 
    local fn="$1";
    local destination;
    if [ -d "$fn" ]; then
        destination="$(stripHash "$fn")";
        if [ -e "$destination" ]; then
            echo "Cannot copy $fn to $destination: destination already exists!";
            echo "Did you specify two \"srcs\" with the same \"name\"?";
            return 1;
        fi;
        cp -r --preserve=timestamps --reflink=auto -- "$fn" "$destination";
    else
        case "$fn" in 
            *.tar.xz | *.tar.lzma | *.txz)
                ( XZ_OPT="--threads=$NIX_BUILD_CORES" xz -d < "$fn";
                true ) | tar xf - --mode=+w --warning=no-timestamp
            ;;
            *.tar | *.tar.* | *.tgz | *.tbz2 | *.tbz)
                tar xf "$fn" --mode=+w --warning=no-timestamp
            ;;
            *)
                return 1
            ;;
        esac;
    fi
}
patchELF ()
{
 
    local dir="$1";
    [ -e "$dir" ] || return 0;
    echo "shrinking RPATHs of ELF executables and libraries in $dir";
    local i;
    while IFS= read -r -d '' i; do
        if [[ "$i" =~ .build-id ]]; then
            continue;
        fi;
        if ! isELF "$i"; then
            continue;
        fi;
        echo "shrinking $i";
        patchelf --shrink-rpath "$i" || true;
    done < <(find "$dir" -type f -print0)
}
showPhaseHeader ()
{
 
    local phase="$1";
    echo "Running phase: $phase";
    if [[ -z ${NIX_LOG_FD-} ]]; then
        return;
    fi;
    printf "@nix { \"action\": \"setPhase\", \"phase\": \"%s\" }\n" "$phase" >&"$NIX_LOG_FD"
}
stripDirs ()
{
 
    local cmd="$1";
    local ranlibCmd="$2";
    local paths="$3";
    local stripFlags="$4";
    local excludeFlags=();
    local pathsNew=;
    [ -z "$cmd" ] && echo "stripDirs: Strip command is empty" 1>&2 && exit 1;
    [ -z "$ranlibCmd" ] && echo "stripDirs: Ranlib command is empty" 1>&2 && exit 1;
    local pattern;
    if [ -n "${stripExclude:-}" ]; then
        for pattern in "${stripExclude[@]}";
        do
            excludeFlags+=(-a '!' '(' -name "$pattern" -o -wholename "$prefix/$pattern" ')');
        done;
    fi;
    local p;
    for p in ${paths};
    do
        if [ -e "$prefix/$p" ]; then
            pathsNew="${pathsNew} $prefix/$p";
        fi;
    done;
    paths=${pathsNew};
    if [ -n "${paths}" ]; then
        echo "stripping (with command $cmd and flags $stripFlags) in $paths";
        local striperr;
        striperr="$(mktemp --tmpdir="$TMPDIR" 'striperr.XXXXXX')";
        find $paths -type f "${excludeFlags[@]}" -a '!' -path "$prefix/lib/debug/*" -printf '%D-%i,%p\0' | sort -t, -k1,1 -u -z | cut -d, -f2- -z | xargs -r -0 -n1 -P "$NIX_BUILD_CORES" -- $cmd $stripFlags 2> "$striperr" || exit_code=$?;
        [[ "$exit_code" = 123 || -z "$exit_code" ]] || ( cat "$striperr" 1>&2 && exit 1 );
        rm "$striperr";
        find $paths -name '*.a' -type f -exec $ranlibCmd '{}' \; 2> /dev/null;
    fi
}
substitute ()
{
 
    local input="$1";
    local output="$2";
    shift 2;
    if [ ! -f "$input" ]; then
        echo "substitute(): ERROR: file '$input' does not exist" 1>&2;
        return 1;
    fi;
    local content;
    consumeEntire content < "$input";
    if [ -e "$output" ]; then
        chmod +w "$output";
    fi;
    substituteStream content "file '$input'" "$@" > "$output"
}
addToSearchPath ()
{
 
    addToSearchPathWithCustomDelimiter ":" "$@"
}
_updateSourceDateEpochFromSourceRoot ()
{
 
    if [ -n "$sourceRoot" ]; then
        updateSourceDateEpoch "$sourceRoot";
    fi
}
addPythonPath ()
{
 
    addToSearchPathWithCustomDelimiter : PYTHONPATH $1/lib/python3.13/site-packages
}
_multioutDevs ()
{
 
    if [ "$(getAllOutputNames)" = "out" ] || [ -z "${moveToDev-1}" ]; then
        return;
    fi;
    moveToOutput include "${!outputInclude}";
    moveToOutput lib/pkgconfig "${!outputDev}";
    moveToOutput share/pkgconfig "${!outputDev}";
    moveToOutput lib/cmake "${!outputDev}";
    moveToOutput share/aclocal "${!outputDev}";
    for f in "${!outputDev}"/{lib,share}/pkgconfig/*.pc;
    do
        echo "Patching '$f' includedir to output ${!outputInclude}";
        sed -i "/^includedir=/s,=\${prefix},=${!outputInclude}," "$f";
    done
}
getAllOutputNames ()
{
 
    if [ -n "$__structuredAttrs" ]; then
        echo "${!outputs[*]}";
    else
        echo "$outputs";
    fi
}
patchShebangs ()
{
 
    local pathName;
    local update=false;
    while [[ $# -gt 0 ]]; do
        case "$1" in 
            --host)
                pathName=HOST_PATH;
                shift
            ;;
            --build)
                pathName=PATH;
                shift
            ;;
            --update)
                update=true;
                shift
            ;;
            --)
                shift;
                break
            ;;
            -* | --*)
                echo "Unknown option $1 supplied to patchShebangs" 1>&2;
                return 1
            ;;
            *)
                break
            ;;
        esac;
    done;
    echo "patching script interpreter paths in $@";
    local f;
    local oldPath;
    local newPath;
    local arg0;
    local args;
    local oldInterpreterLine;
    local newInterpreterLine;
    if [[ $# -eq 0 ]]; then
        echo "No arguments supplied to patchShebangs" 1>&2;
        return 0;
    fi;
    local f;
    while IFS= read -r -d '' f; do
        isScript "$f" || continue;
        read -r oldInterpreterLine < "$f" || [ "$oldInterpreterLine" ];
        read -r oldPath arg0 args <<< "${oldInterpreterLine:2}";
        if [[ -z "${pathName:-}" ]]; then
            if [[ -n $strictDeps && $f == "$NIX_STORE"* ]]; then
                pathName=HOST_PATH;
            else
                pathName=PATH;
            fi;
        fi;
        if [[ "$oldPath" == *"/bin/env" ]]; then
            if [[ $arg0 == "-S" ]]; then
                arg0=${args%% *};
                [[ "$args" == *" "* ]] && args=${args#* } || args=;
                newPath="$(PATH="${!pathName}" type -P "env" || true)";
                args="-S $(PATH="${!pathName}" type -P "$arg0" || true) $args";
            else
                if [[ $arg0 == "-"* || $arg0 == *"="* ]]; then
                    echo "$f: unsupported interpreter directive \"$oldInterpreterLine\" (set dontPatchShebangs=1 and handle shebang patching yourself)" 1>&2;
                    exit 1;
                else
                    newPath="$(PATH="${!pathName}" type -P "$arg0" || true)";
                fi;
            fi;
        else
            if [[ -z $oldPath ]]; then
                oldPath="/bin/sh";
            fi;
            newPath="$(PATH="${!pathName}" type -P "$(basename "$oldPath")" || true)";
            args="$arg0 $args";
        fi;
        newInterpreterLine="$newPath $args";
        newInterpreterLine=${newInterpreterLine%${newInterpreterLine##*[![:space:]]}};
        if [[ -n "$oldPath" && ( "$update" == true || "${oldPath:0:${#NIX_STORE}}" != "$NIX_STORE" ) ]]; then
            if [[ -n "$newPath" && "$newPath" != "$oldPath" ]]; then
                echo "$f: interpreter directive changed from \"$oldInterpreterLine\" to \"$newInterpreterLine\"";
                escapedInterpreterLine=${newInterpreterLine//\\/\\\\};
                timestamp=$(stat --printf "%y" "$f");
                tmpFile=$(mktemp -t patchShebangs.XXXXXXXXXX);
                sed -e "1 s|.*|#\!$escapedInterpreterLine|" "$f" > "$tmpFile";
                local restoreReadOnly;
                if [[ ! -w "$f" ]]; then
                    chmod +w "$f";
                    restoreReadOnly=true;
                fi;
                cat "$tmpFile" > "$f";
                rm "$tmpFile";
                if [[ -n "${restoreReadOnly:-}" ]]; then
                    chmod -w "$f";
                fi;
                touch --date "$timestamp" "$f";
            fi;
        fi;
    done < <(find "$@" -type f -perm -0100 -print0)
}
updateSourceDateEpoch ()
{
 
    local path="$1";
    [[ $path == -* ]] && path="./$path";
    local -a res=($(find "$path" -type f -not -newer "$NIX_BUILD_TOP/.." -printf '%T@ "%p"\0' | sort -n --zero-terminated | tail -n1 --zero-terminated | head -c -1));
    local time="${res[0]//\.[0-9]*/}";
    local newestFile="${res[1]}";
    if [ "${time:-0}" -gt "$SOURCE_DATE_EPOCH" ]; then
        echo "setting SOURCE_DATE_EPOCH to timestamp $time of file $newestFile";
        export SOURCE_DATE_EPOCH="$time";
        local now="$(date +%s)";
        if [ "$time" -gt $((now - 60)) ]; then
            echo "warning: file $newestFile may be generated; SOURCE_DATE_EPOCH may be non-deterministic";
        fi;
    fi
}
PATH="$PATH${nix_saved_PATH:+:$nix_saved_PATH}"
XDG_DATA_DIRS="$XDG_DATA_DIRS${nix_saved_XDG_DATA_DIRS:+:$nix_saved_XDG_DATA_DIRS}"

eval "${shellHook:-}"
