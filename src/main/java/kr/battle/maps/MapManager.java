package kr.battle.maps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;

/**
 * 맵 4개(야생 1 + 도시 3)의 생명주기를 관리한다.
 *
 * <pre>
 *  [서버 시작]  dirty 인 맵  -> (폴더 삭제) -> 원본 복사 / 새 야생 -> 월드 열기
 *               깨끗한 맵   -> 그대로 월드 열기
 *  [게임 중]    #game 상태가 4~6(맵 로딩/배틀/시상식)이면 그 맵을 dirty 로 표시 (상태 파일에 즉시 저장)
 *  [게임 끝]    로비로 돌아온 뒤 0.5초 지나면 dirty 맵을  월드 삭제 -> 옛 월드 객체 닫기(밀린 저장 마무리) -> 폴더 삭제
 *               -> 원본 복사(도시: region/entities/poi 만) / 새 시드(야생) -> 월드 다시 열기
 *  [상태 알림]  #maps bt.state = 1 준비됨 / 2 초기화 중 / 3 오류,  #st1~#st4 = 맵별 상태 (데이터팩이 읽음)
 *  [수동 요청]  데이터팩이 #resetreq bt.state 를 1 로 두면 모든 맵을 '쓴 맵'으로 보고 로비에서 다시 채운다
 * </pre>
 */
final class MapManager {
	private static final Logger LOG = BattleMaps.LOG;

	/** 로비(0) 또는 시상식(6) 상태가 이만큼 이어지면 초기화 시작 (10틱 = 0.5초) */
	private static final int QUIET_TICKS = 10;
	/** 월드 삭제를 이만큼(틱) 기다려도 안 사라지면 실패로 본다 */
	private static final int DELETE_TIMEOUT = 400;
	/** 실패 뒤 재시도까지 기다리는 틱 */
	private static final int RETRY_TICKS = 1200;
	/** 옛 월드를 닫는 데(밀린 파일 쓰기 마무리) 이만큼(틱) 걸려도 기다리지 않고 진행 */
	private static final int CLOSE_TIMEOUT = 400;
	/** 원본에서 region 폴더를 못 찾았을 때 다시 찾아보기까지의 틱 (5초) */
	private static final int SOURCE_RECHECK_TICKS = 100;
	/** 폴더를 못 지웠을 때 다시 시도하기까지의 틱 (5분) */
	private static final int DELETE_RETRY_TICKS = 6000;
	/** 원본에서 복사하는 폴더들 (그 밖의 파일은 가져가지 않는다) */
	private static final String[] COPY_DIRS = {"region", "entities", "poi"};

	private static final String OBJ = "bt.state";

	/** 야생: 데이터팩 dimension 파일(arena.json)의 generator 와 같은 내용 */
	private static final String ARENA_GENERATOR = """
		{
		  "type": "minecraft:noise",
		  "settings": "minecraft:overworld",
		  "biome_source": { "type": "minecraft:multi_noise", "preset": "minecraft:overworld" }
		}
		""";

	/** 도시: 원본 파일이 없는 바깥쪽을 채우는 슈퍼플랫 (데이터팩 dimension 파일의 generator 와 같은 내용) */
	private static final String CITY_GENERATOR = """
		{
		  "type": "minecraft:flat",
		  "settings": {
		    "biome": "minecraft:plains",
		    "features": false,
		    "lakes": false,
		    "structure_overrides": [],
		    "layers": [
		      { "block": "minecraft:bedrock", "height": 1 },
		      { "block": "minecraft:dirt", "height": 2 },
		      { "block": "minecraft:grass_block", "height": 1 }
		    ]
		  }
		}
		""";

	private static final ResourceKey<DimensionType> DIM_TYPE =
		ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath("minecraft", "overworld"));

	private final MinecraftServer server;
	private final Path dimDir;
	private final Path originals;
	private final Path stateFile;
	private final List<MapSlot> slots = new ArrayList<>();
	private final ExecutorService io;
	/** 옛 월드 닫기 전용 스레드 (혹시 오래 걸려도 원본 복사가 그 뒤에 막히지 않게 따로 둔다) */
	private final ExecutorService closer;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private long arenaSeed;
	private int quiet;
	private int lastState = Integer.MIN_VALUE;
	private int published = -1;
	private int[] publishedStatus = new int[0];
	private int sincePublish;
	private int errorLogCooldown;

	MapManager(MinecraftServer server) {
		this.server = server;
		Path root = server.getWorldPath(LevelResource.ROOT);
		this.dimDir = root.resolve("dimensions").resolve("battle");
		this.originals = root.resolve("battle_map_originals");
		this.stateFile = root.resolve("battle_maps_state.json");
		this.io = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "BattleMaps-IO");
			t.setDaemon(true);
			return t;
		});
		this.closer = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "BattleMaps-Close");
			t.setDaemon(true);
			return t;
		});

		slots.add(new MapSlot(1, "arena", true, ARENA_GENERATOR));
		slots.add(new MapSlot(2, "city1", false, CITY_GENERATOR));
		slots.add(new MapSlot(3, "city2", false, CITY_GENERATOR));
		slots.add(new MapSlot(4, "city3", false, CITY_GENERATOR));
		for (MapSlot s : slots) {
			s.dir = resolveDir(s);
		}
	}

	/**
	 * 맵 월드의 저장 폴더. 게임(LevelStorageAccess.getDimensionPath)에게 직접 물어보고,
	 * 물어보지 못하면 <월드>/dimensions/battle/<이름> 으로 가정한다.
	 */
	private Path resolveDir(MapSlot s) {
		Path fallback = dimDir.resolve(s.name);
		try {
			Field field = MinecraftServer.class.getDeclaredField("storageSource");
			field.setAccessible(true);
			Object access = field.get(server);
			Method method = access.getClass().getMethod("getDimensionPath", ResourceKey.class);
			Object result = method.invoke(access, s.levelKey);
			if (result instanceof Path path) {
				return path;
			}
		} catch (Throwable t) {
			LOG.info("[BattleMaps] {} 저장 폴더를 게임에게 묻지 못해 기본 위치를 씁니다 ({})", s.id, t.toString());
		}
		return fallback;
	}

	// ------------------------------------------------------------------ 시작 / 종료

	void onStart() {
		for (MapSlot s : slots) {
			LOG.info("[BattleMaps] 월드 폴더: {} -> {}", s.id, s.dir);
		}
		LOG.info("[BattleMaps] 원본 맵 폴더: {}", originals);
		loadState();
		for (MapSlot s : slots) {
			if (server.getLevel(s.levelKey) != null) {
				LOG.error("[BattleMaps] {} 월드가 이미 존재합니다. 데이터팩의 data/battle/dimension/ 폴더(arena.json, city1~3.json)를 지워 주세요. 이 맵은 관리하지 않습니다.", s.id);
				s.phase = MapSlot.Phase.CLOSED;
				s.cooldown = Integer.MAX_VALUE;
				s.unmanaged = true;
				continue;
			}
			if (s.dirty) {
				startReset(s);
			}
			if (s.phase == MapSlot.Phase.CLOSED) {
				openLevel(s);
			}
		}
		publish(true);
	}

	void onStop() {
		try {
			saveState();
			writeScore("#maps", 0);
		} catch (Throwable t) {
			LOG.warn("[BattleMaps] 종료 처리 중 오류", t);
		}
		io.shutdownNow();
		closer.shutdownNow();
	}

	// ------------------------------------------------------------------ 매 틱

	void tick() {
		try {
			tickInner();
		} catch (Throwable t) {
			if (errorLogCooldown <= 0) {
				LOG.error("[BattleMaps] tick 오류", t);
				errorLogCooldown = 200;
			}
		}
		if (errorLogCooldown > 0) {
			errorLogCooldown--;
		}
	}

	private void tickInner() {
		int state = readScore("#game");
		int map = readScore("#map");

		// 1) 맵 로딩(4)에 들어서는 순간 그 맵을 '쓴 맵'으로 표시 (서버가 도중에 꺼져도 다음에 초기화되도록 바로 저장)
		//    (시상식(6) 동안 초기화가 끝난 맵이 다시 표시되지 않도록, 상태가 바뀌는 순간에만 확인)
		if (state >= 4 && state <= 6 && lastState < 4) {
			MapSlot used = slotForMap(map);
			if (used != null && !used.dirty) {
				used.dirty = true;
				saveState();
				LOG.info("[BattleMaps] {} 맵 사용 시작 - 게임이 끝나면 초기화합니다.", used.id);
			}
		}
		lastState = state;

		// 1-2) 데이터팩의 수동 초기화 요청 (/function battle:admin/maps_reset): 모든 맵을 '쓴 맵'으로 표시 -> 로비에서 곧바로 초기화
		if (readScore("#resetreq") == 1) {
			writeScore("#resetreq", 0);
			for (MapSlot s : slots) {
				if (!s.unmanaged) {
					s.dirty = true;
					s.sourceWarned = false;
					if (s.phase == MapSlot.Phase.OPEN || s.phase == MapSlot.Phase.CLOSED) {
						s.cooldown = 0;
					}
				}
			}
			saveState();
			LOG.info("[BattleMaps] 수동 초기화 요청을 받았습니다. 로비에서 모든 맵을 새로 준비합니다.");
		}

		// 2) 로비(0) 또는 시상식(6)이 잠시 이어지면 쓴 맵 초기화 시작
		if (state == 0 || state == 6) {
			quiet++;
		} else {
			quiet = 0;
		}

		for (MapSlot s : slots) {
			if (s.cooldown > 0 && s.cooldown != Integer.MAX_VALUE) {
				s.cooldown--;
			}
			advance(s);
		}

		if (quiet >= QUIET_TICKS) {
			for (MapSlot s : slots) {
				boolean idle = s.phase == MapSlot.Phase.OPEN || s.phase == MapSlot.Phase.CLOSED;
				if (s.dirty && !s.unmanaged && idle && s.cooldown == 0) {
					startReset(s);
				}
			}
		}

		publish(false);
	}

	private MapSlot slotForMap(int map) {
		for (MapSlot s : slots) {
			if (s.mapIndex == map) {
				return s;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ 초기화 단계

	/**
	 * 초기화 시작. 도시는 원본 폴더에서 region 이 든 곳을 먼저 찾고(없으면 폴더를 건드리지 않고 건너뜀),
	 * 월드가 열려 있으면 먼저 삭제를 요청한다.
	 */
	private void startReset(MapSlot s) {
		Path src = null;
		if (!s.wild) {
			src = findSource(s);
			if (src == null) {
				// 원본이 없으면 폴더를 건드리지 않는다 (직접 넣어 둔 맵 보호). dirty 는 그대로 두고 잠시 뒤 다시 찾아본다.
				// 원본을 넣으면 곧바로(로비 상태일 때) 새 복사본으로 교체된다.
				s.sourceProblem = true;
				s.cooldown = SOURCE_RECHECK_TICKS;
				if (!s.sourceWarned) {
					s.sourceWarned = true;
					LOG.warn("[BattleMaps] {} 의 원본을 찾지 못해 초기화를 건너뜁니다. {} 아래에 region 폴더(.mca 파일이 든)가 있어야 합니다.",
						s.id, originals.resolve(s.name));
				}
				return;
			}
			if (!src.equals(originals.resolve(s.name))) {
				LOG.info("[BattleMaps] {} 원본의 region 폴더를 {} 에서 찾았습니다.", s.id, src);
			}
		}
		s.sourceProblem = false;
		s.sourceWarned = false;
		s.source = src;
		LOG.info("[BattleMaps] {} 초기화 시작", s.id);
		s.waitTicks = 0;
		s.phase = MapSlot.Phase.DELETING;
		ServerLevel open = server.getLevel(s.levelKey);
		if (s.handle != null && open != null) {
			s.oldLevel = open;
			s.handle.delete();
		} else {
			s.handle = null;
			s.oldLevel = null;
			afterDeleted(s);
		}
	}

	/** 도시 원본에서 region 폴더(.mca 파일이 든)를 가진 폴더를 찾는다. 없으면 null. */
	private Path findSource(MapSlot s) {
		Path base = originals.resolve(s.name);
		if (!Files.isDirectory(base)) {
			return null;
		}
		// 1) 안내한 위치: cityN/region
		if (hasMca(base.resolve("region"))) {
			return base;
		}
		// 2) 26.x 월드를 통째로 넣은 경우: cityN/dimensions/minecraft/overworld/region
		Path overworld = base.resolve("dimensions").resolve("minecraft").resolve("overworld");
		if (hasMca(overworld.resolve("region"))) {
			return overworld;
		}
		// 3) 그 밖에 폴더가 더 겹쳐 있는 경우: 6단계 안에서 가장 얕은 region (네더/엔드 폴더는 제외)
		List<Path> found = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(base, 6)) {
			walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("region"))
				.filter(p -> !inOtherDimension(base.relativize(p)))
				.filter(this::hasMca)
				.forEach(found::add);
		} catch (IOException | UncheckedIOException e) {
			LOG.warn("[BattleMaps] {} 원본 폴더를 훑는 중 오류: {}", s.id, e.toString());
		}
		found.sort(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString));
		return found.isEmpty() ? null : found.get(0).getParent();
	}

	private static boolean inOtherDimension(Path relative) {
		for (Path part : relative) {
			String n = part.toString().toLowerCase(Locale.ROOT);
			if (n.equals("dim-1") || n.equals("dim1") || n.equals("the_nether") || n.equals("the_end")) {
				return true;
			}
		}
		return false;
	}

	private boolean hasMca(Path regionDir) {
		return countMca(regionDir) > 0;
	}

	private static int countMca(Path regionDir) {
		if (!Files.isDirectory(regionDir)) {
			return 0;
		}
		try (Stream<Path> list = Files.list(regionDir)) {
			return (int) list.filter(p -> p.getFileName().toString().endsWith(".mca")).count();
		} catch (IOException | UncheckedIOException e) {
			return 0;
		}
	}

	private void advance(MapSlot s) {
		switch (s.phase) {
			case DELETING -> {
				if (server.getLevel(s.levelKey) == null) {
					s.handle = null;
					beginClose(s);
				} else if (++s.waitTicks > DELETE_TIMEOUT) {
					LOG.error("[BattleMaps] {} 월드 삭제가 끝나지 않습니다. 잠시 뒤 다시 시도합니다.", s.id);
					s.oldLevel = null;
					s.phase = MapSlot.Phase.OPEN;
					s.cooldown = RETRY_TICKS;
				}
			}
			case CLOSING -> {
				boolean done = s.closing == null || s.closing.isDone();
				if (done || ++s.waitTicks > CLOSE_TIMEOUT) {
					if (!done) {
						LOG.warn("[BattleMaps] {} 옛 월드를 닫는 데 오래 걸려 기다리지 않고 진행합니다.", s.id);
					}
					s.closing = null;
					afterDeleted(s);
				}
			}
			case COPYING -> {
				if (s.copy != null && s.copy.isDone()) {
					boolean ok = !s.copy.isCompletedExceptionally();
					if (!ok) {
						try {
							s.copy.join();
						} catch (Throwable t) {
							LOG.error("[BattleMaps] {} 원본 복사 실패", s.id, t);
						}
					}
					s.copy = null;
					finishReset(s, ok);
				}
			}
			default -> {
			}
		}
	}

	/**
	 * 게임이 월드를 목록에서 뺀 직후. 게임(Fantasy)은 월드 객체를 닫지 않고 폴더만 지우기 때문에,
	 * 닫지 않은 옛 월드가 밀린 청크 저장을 나중에 새 폴더에 써 넣거나(옛 플레이 흔적이 새 복사본을 덮어씀),
	 * 파일을 붙잡고 있을 수 있다. 그래서 옛 월드 객체를 직접 닫아서(남은 쓰기를 모두 끝내고 파일을 놓게 함) 정리한다.
	 */
	private void beginClose(MapSlot s) {
		ServerLevel old = s.oldLevel;
		s.oldLevel = null;
		if (Files.exists(s.dir)) {
			// 게임의 폴더 삭제가 덜 끝났다 (파일 잠금 등). 게임이 '종료 때 지우기'를 예약했을 수 있으므로,
			// 서버를 끌 때 이 맵을 '쓴 맵'으로 기록해서 다음 실행에 원본으로 다시 채우게 한다.
			s.exitRisk = true;
			LOG.warn("[BattleMaps] {} 폴더가 월드 삭제 뒤에도 남아 있습니다. 직접 정리합니다.", s.id);
		}
		if (old == null) {
			afterDeleted(s);
			return;
		}
		s.phase = MapSlot.Phase.CLOSING;
		s.waitTicks = 0;
		s.closing = CompletableFuture.runAsync(() -> {
			try {
				old.close();
			} catch (Throwable t) {
				LOG.warn("[BattleMaps] {} 옛 월드를 닫는 중 오류 (무시하고 계속합니다): {}", s.id, t.toString());
			}
		}, closer);
	}

	/** 월드가 닫힌 뒤: 폴더 정리 -> (야생) 새 시드로 바로 열기 / (도시) 원본 복사 시작 */
	private void afterDeleted(MapSlot s) {
		Path dir = s.dir;
		if (!FileTools.deleteTree(dir)) {
			LOG.error("[BattleMaps] {} 폴더를 지우지 못했습니다 (파일이 열려 있을 수 있음). 5분 뒤 다시 시도합니다.", s.id);
			s.exitRisk = true;
			s.phase = MapSlot.Phase.CLOSED;
			s.cooldown = DELETE_RETRY_TICKS;
			openLevelIfPossible(s);
			return;
		}

		if (s.wild) {
			arenaSeed = ThreadLocalRandom.current().nextLong();
			finishReset(s, true);
			return;
		}

		Path src = s.source;
		s.phase = MapSlot.Phase.COPYING;
		s.copy = CompletableFuture.runAsync(() -> {
			try {
				copyOriginal(src, dir);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, io);
		LOG.info("[BattleMaps] {} 원본 복사 중... ({})", s.id, src);
	}

	/** 원본 폴더에서 region / entities / poi 만 가져온다. */
	private static void copyOriginal(Path src, Path dst) throws IOException {
		Files.createDirectories(dst);
		for (String name : COPY_DIRS) {
			Path from = src.resolve(name);
			if (Files.isDirectory(from)) {
				FileTools.copyTree(from, dst.resolve(name));
			}
		}
	}

	/** 폴더 준비가 끝났다 -> (도시는 복사 결과를 확인하고) 월드를 열고, 성공하면 '깨끗함'으로 표시 */
	private void finishReset(MapSlot s, boolean filesOk) {
		int regions = -1;
		if (!s.wild && filesOk) {
			regions = countMca(s.dir.resolve("region"));
			if (regions <= 0) {
				filesOk = false;
				LOG.error("[BattleMaps] {} 복사가 끝났는데 {} 에 .mca 파일이 없습니다.", s.id, s.dir.resolve("region"));
			}
		}
		boolean opened = openLevel(s);
		if (filesOk && opened) {
			s.dirty = false;
			s.sourceProblem = false;
			LOG.info("[BattleMaps] {} 새 맵 준비 완료{}", s.id, regions >= 0 ? " (region 파일 " + regions + "개)" : "");
		} else {
			s.cooldown = RETRY_TICKS;
		}
		saveState();
	}

	private void openLevelIfPossible(MapSlot s) {
		if (server.getLevel(s.levelKey) == null) {
			openLevel(s);
		}
	}

	// ------------------------------------------------------------------ 월드 열기

	private boolean openLevel(MapSlot s) {
		try {
			ChunkGenerator generator = buildGenerator(s);
			RuntimeLevelConfig config = new RuntimeLevelConfig()
				.setDimensionType(DIM_TYPE)
				.setGenerator(generator)
				.setSeed(s.wild ? arenaSeed : 0L);
			// 게임규칙(커맨드 블록 금지 등) · 난이도 · 시계를 오버월드와 똑같이 따라간다
			config.mirrorOverworldGameState();

			s.handle = Fantasy.get(server).getOrOpenPersistentLevel(s.id, config);
			s.phase = MapSlot.Phase.OPEN;
			return true;
		} catch (Throwable t) {
			LOG.error("[BattleMaps] {} 월드를 열지 못했습니다.", s.id, t);
			s.handle = null;
			s.phase = MapSlot.Phase.CLOSED;
			return false;
		}
	}

	private ChunkGenerator buildGenerator(MapSlot s) {
		JsonElement json = JsonParser.parseString(s.generatorJson);
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		DataResult<ChunkGenerator> result = ChunkGenerator.CODEC.parse(ops, json);
		Optional<ChunkGenerator> generator = result.result();
		if (generator.isEmpty()) {
			throw new IllegalStateException("지형 생성기 JSON을 읽지 못했습니다: " + result);
		}
		return generator.get();
	}

	// ------------------------------------------------------------------ 상태 파일

	private void loadState() {
		boolean fresh = !Files.exists(stateFile);
		if (!fresh) {
			try {
				JsonObject o = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonArray dirty = o.getAsJsonArray("dirty");
				for (JsonElement e : dirty) {
					for (MapSlot s : slots) {
						if (s.name.equals(e.getAsString())) {
							s.dirty = true;
						}
					}
				}
				arenaSeed = o.get("arenaSeed").getAsLong();
				return;
			} catch (Exception e) {
				LOG.warn("[BattleMaps] 상태 파일을 읽지 못해 모든 맵을 초기화합니다: {}", e.toString());
			}
		} else {
			LOG.info("[BattleMaps] 처음 실행입니다. 모든 맵을 초기화합니다.");
		}
		for (MapSlot s : slots) {
			s.dirty = true;
		}
		arenaSeed = ThreadLocalRandom.current().nextLong();
		saveState();
	}

	private void saveState() {
		JsonObject o = new JsonObject();
		o.addProperty("note", "battle_maps 모드가 관리하는 파일입니다. dirty = 아직 새 복사본이 아닌(게임에 쓰인) 맵.");
		JsonArray dirty = new JsonArray();
		for (MapSlot s : slots) {
			if (s.dirty || s.exitRisk) {
				dirty.add(s.name);
			}
		}
		o.add("dirty", dirty);
		o.addProperty("arenaSeed", arenaSeed);
		try {
			Files.writeString(stateFile, gson.toJson(o), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("[BattleMaps] 상태 파일을 저장하지 못했습니다: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------ 데이터팩과 스코어보드로 대화

	private int readScore(String holder) {
		Scoreboard board = server.getScoreboard();
		Objective objective = board.getObjective(OBJ);
		if (objective == null) {
			return Integer.MIN_VALUE;
		}
		ReadOnlyScoreInfo info = board.getPlayerScoreInfo(ScoreHolder.forNameOnly(holder), objective);
		return info == null ? Integer.MIN_VALUE : info.value();
	}

	private boolean writeScore(String holder, int value) {
		Scoreboard board = server.getScoreboard();
		Objective objective = board.getObjective(OBJ);
		if (objective == null) {
			return false;
		}
		board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(holder), objective).set(value);
		return true;
	}

	/** 맵 하나의 상태 번호 (#st1~#st4): 0 = 새 복사본, 1 = 쓴 맵(초기화 대기), 2 = 초기화 중, 3 = 월드를 못 열었음, 4 = 쓴 맵인데 원본을 못 찾음 */
	private static int statusOf(MapSlot s) {
		return switch (s.phase) {
			case CLOSED -> 3;
			case DELETING, CLOSING, COPYING -> 2;
			case OPEN -> s.dirty ? (s.sourceProblem ? 4 : 1) : 0;
		};
	}

	/**
	 * #maps bt.state : 1 = 모든 맵 준비됨, 2 = 초기화 중, 3 = 오류(월드를 못 연 맵이 있음)
	 * #st1~#st4 bt.state : 맵별 상태 (statusOf 참고) - 데이터팩의 /function battle:admin/maps 가 보여 줌
	 */
	private void publish(boolean force) {
		boolean busy = false;
		boolean error = false;
		int[] status = new int[slots.size()];
		for (int i = 0; i < slots.size(); i++) {
			MapSlot s = slots.get(i);
			status[i] = statusOf(s);
			if (s.phase == MapSlot.Phase.DELETING || s.phase == MapSlot.Phase.CLOSING || s.phase == MapSlot.Phase.COPYING) {
				busy = true;
			} else if (s.phase == MapSlot.Phase.CLOSED) {
				error = true;
			}
		}
		int value = error ? 3 : (busy ? 2 : 1);
		sincePublish++;
		if (force || value != published || !Arrays.equals(status, publishedStatus) || sincePublish >= 100) {
			if (writeScore("#maps", value)) {
				for (int i = 0; i < slots.size(); i++) {
					writeScore("#st" + slots.get(i).mapIndex, status[i]);
				}
				if (value != published) {
					LOG.info("[BattleMaps] 맵 상태: {}", value == 1 ? "준비됨" : (value == 2 ? "초기화 중" : "오류"));
				}
				published = value;
				publishedStatus = status;
				sincePublish = 0;
			}
		}
	}
}
