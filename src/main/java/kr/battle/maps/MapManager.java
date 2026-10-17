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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
 *  [게임 끝]    로비로 돌아온 뒤 0.5초 지나면 dirty 맵을  월드 삭제 -> 폴더 삭제 -> 원본 복사(도시) / 새 시드(야생) -> 월드 다시 열기
 *  [상태 알림]  #maps bt.state = 1 준비됨 / 2 초기화 중 / 3 오류  (데이터팩이 읽음)
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
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private long arenaSeed;
	private int quiet;
	private int lastState = Integer.MIN_VALUE;
	private int published = -1;
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

		slots.add(new MapSlot(1, "arena", true, ARENA_GENERATOR));
		slots.add(new MapSlot(2, "city1", false, CITY_GENERATOR));
		slots.add(new MapSlot(3, "city2", false, CITY_GENERATOR));
		slots.add(new MapSlot(4, "city3", false, CITY_GENERATOR));
	}

	// ------------------------------------------------------------------ 시작 / 종료

	void onStart() {
		LOG.info("[BattleMaps] 월드 폴더: {}", dimDir);
		LOG.info("[BattleMaps] 원본 맵 폴더: {}", originals);
		loadState();
		for (MapSlot s : slots) {
			if (server.getLevel(s.levelKey) != null) {
				LOG.error("[BattleMaps] {} 월드가 이미 존재합니다. 데이터팩의 data/battle/dimension/ 폴더(arena.json, city1~3.json)를 지워 주세요. 이 맵은 관리하지 않습니다.", s.id);
				s.phase = MapSlot.Phase.CLOSED;
				s.cooldown = Integer.MAX_VALUE;
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
				if (s.dirty && s.phase == MapSlot.Phase.OPEN && s.cooldown == 0 && canReset(s)) {
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

	/** 야생은 항상 초기화 가능, 도시는 원본 폴더가 있어야 가능 */
	private boolean canReset(MapSlot s) {
		return s.wild || Files.isDirectory(originals.resolve(s.name));
	}

	/** 초기화 시작. 월드가 열려 있으면 먼저 삭제를 요청한다. */
	private void startReset(MapSlot s) {
		if (!canReset(s)) {
			// 원본이 없으면 폴더를 건드리지 않는다 (직접 넣어 둔 맵 보호). dirty 는 그대로 두므로,
			// 나중에 원본을 넣고 월드를 다시 열면 그때 새 복사본으로 교체된다.
			LOG.warn("[BattleMaps] {} 의 원본이 없어 초기화를 건너뜁니다. 원본을 {} 에 넣어 주세요.", s.id, originals.resolve(s.name));
			return;
		}
		LOG.info("[BattleMaps] {} 초기화 시작", s.id);
		s.waitTicks = 0;
		s.phase = MapSlot.Phase.DELETING;
		if (s.handle != null && server.getLevel(s.levelKey) != null) {
			s.handle.delete();
		} else {
			s.handle = null;
			afterDeleted(s);
		}
	}

	private void advance(MapSlot s) {
		switch (s.phase) {
			case DELETING -> {
				if (server.getLevel(s.levelKey) == null) {
					s.handle = null;
					afterDeleted(s);
				} else if (++s.waitTicks > DELETE_TIMEOUT) {
					LOG.error("[BattleMaps] {} 월드 삭제가 끝나지 않습니다. 잠시 뒤 다시 시도합니다.", s.id);
					s.phase = MapSlot.Phase.OPEN;
					s.cooldown = RETRY_TICKS;
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

	/** 월드가 닫힌 뒤: 폴더 정리 -> (야생) 새 시드로 바로 열기 / (도시) 원본 복사 시작 */
	private void afterDeleted(MapSlot s) {
		Path dir = dimDir.resolve(s.name);
		if (!FileTools.deleteTree(dir)) {
			LOG.error("[BattleMaps] {} 폴더를 지우지 못했습니다 (파일이 열려 있을 수 있음). 서버를 껐다 켜면 다시 시도합니다.", s.id);
			s.phase = MapSlot.Phase.CLOSED;
			s.cooldown = Integer.MAX_VALUE;
			openLevelIfPossible(s);
			return;
		}

		if (s.wild) {
			arenaSeed = ThreadLocalRandom.current().nextLong();
			finishReset(s, true);
			return;
		}

		Path orig = originals.resolve(s.name);
		s.phase = MapSlot.Phase.COPYING;
		s.copy = CompletableFuture.runAsync(() -> {
			try {
				FileTools.copyTree(orig, dir);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, io);
		LOG.info("[BattleMaps] {} 원본 복사 중...", s.id);
	}

	/** 폴더 준비가 끝났다 -> 월드를 열고, 성공하면 '깨끗함'으로 표시 */
	private void finishReset(MapSlot s, boolean filesOk) {
		boolean opened = openLevel(s);
		if (filesOk && opened) {
			s.dirty = false;
			LOG.info("[BattleMaps] {} 새 맵 준비 완료", s.id);
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
			if (s.dirty) {
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

	/** #maps bt.state : 1 = 모든 맵 준비됨, 2 = 초기화 중, 3 = 오류(월드를 못 연 맵이 있음) */
	private void publish(boolean force) {
		boolean busy = false;
		boolean error = false;
		for (MapSlot s : slots) {
			if (s.phase == MapSlot.Phase.DELETING || s.phase == MapSlot.Phase.COPYING) {
				busy = true;
			} else if (s.phase == MapSlot.Phase.CLOSED) {
				error = true;
			}
		}
		int value = error ? 3 : (busy ? 2 : 1);
		sincePublish++;
		if (force || value != published || sincePublish >= 100) {
			if (writeScore("#maps", value)) {
				if (value != published) {
					LOG.info("[BattleMaps] 맵 상태: {}", value == 1 ? "준비됨" : (value == 2 ? "초기화 중" : "오류"));
				}
				published = value;
				sincePublish = 0;
			}
		}
	}
}
