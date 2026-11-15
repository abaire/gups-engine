gupsMapper = gupsMapper or {}
mudlet = mudlet or {}
mudlet.mapper_script = true

local STANDARD_DIRS = { "n", "ne", "e", "se", "s", "sw", "w", "nw", "u", "d" }
local COMPASS_DIRS = { "n", "ne", "e", "se", "s", "sw", "w", "nw" }
local DIR_OFFSETS = {
  n = { 0, 1, 0 },
  ne = { 1, 1, 0 },
  e = { 1, 0, 0 },
  se = { 1, -1, 0 },
  s = { 0, -1, 0 },
  sw = { -1, -1, 0 },
  w = { -1, 0, 0 },
  nw = { -1, 1, 0 },
  u = { 0, 0, 1 },
  d = { 0, 0, -1 },
}
local IS_STANDARD = {}
for _, d in ipairs(STANDARD_DIRS) do
  IS_STANDARD[d] = true
end
local IS_COMPASS = {}
for _, d in ipairs(COMPASS_DIRS) do
  IS_COMPASS[d] = true
end
local DOOR_STATES = {
  open = 1,
  closed = 2,
  locked = 3,
}

local function deepCopy(val)
  if type(val) ~= "table" then
    return val
  end
  local copy = {}
  for k, v in pairs(val) do
    copy[k] = deepCopy(v)
  end
  return copy
end

local function sortedCustomKeys(exits)
  local keys = {}
  for k, _ in pairs(exits) do
    if not IS_STANDARD[k] then
      keys[#keys + 1] = k
    end
  end
  table.sort(keys)
  return keys
end

local function areaNameTaken(areas, ownId, name)
  for id, other in pairs(areas) do
    if id ~= ownId and other == name then
      return true
    end
  end
  return false
end

-- Mudlet refuses a name another area already has. The zone path tells two same-titled zones
-- apart; the collision counter only guarantees a free name when the player has an area of their
-- own that holds even that.
local function freeAreaName(areas, ownId, areaTitle, areaKey)
  if not areaNameTaken(areas, ownId, areaTitle) then
    return areaTitle
  end
  local withPath = areaTitle .. " (" .. areaKey .. ")"
  local name = withPath
  local n = 0
  while areaNameTaken(areas, ownId, name) do
    n = n + 1
    name = withPath .. " {Collision " .. n .. "}"
  end
  return name
end

local function ensureArea(areaKey, areaTitle)
  local areas = getAreaTableSwap()
  local foundId = nil
  for id, _ in pairs(areas) do
    if getAreaUserData(id, "gups_area") == areaKey then
      foundId = id
      break
    end
  end

  local wantedName = freeAreaName(areas, foundId, areaTitle, areaKey)
  if foundId == nil then
    foundId = addAreaName(wantedName)
    setAreaUserData(foundId, "gups_area", areaKey)
  elseif areas[foundId] ~= wantedName then
    setAreaName(foundId, wantedName)
  end
  return foundId
end

-- A room is only ever created after this has deleted every other room with its label, so when
-- the room at num already has the label there is nothing to find, and the full-map search is skipped.
local function removeStaleRooms(num, label)
  if roomExists(num) and getRoomUserData(num, "gups_label") == label then
    return
  end
  for _, id in ipairs(searchRoomUserData("gups_label", label)) do
    if id ~= num then
      deleteRoom(id)
    end
  end
  if roomExists(num) then
    deleteRoom(num)
  end
end

local function findMoveDir(last, targetNum)
  if not last or type(last.exits) ~= "table" then
    return nil
  end
  for _, d in ipairs(STANDARD_DIRS) do
    if last.exits[d] == targetNum then
      return d
    end
  end
  for _, k in ipairs(sortedCustomKeys(last.exits)) do
    if last.exits[k] == targetNum then
      return k
    end
  end
  return nil
end

local function firstFreeSpot(areaId)
  for r = 0, 20 do
    for y = r, -r, -1 do
      for x = -r, r do
        if math.max(math.abs(x), math.abs(y)) == r and next(getRoomsByPosition(areaId, x, y, 0)) == nil then
          return x, y, 0
        end
      end
    end
  end
  return 0, 0, 0
end

local function placeNewRoom(info, areaId)
  local last = gupsMapper.last
  local dir = findMoveDir(last, info.num)
  local x, y, z
  if dir and DIR_OFFSETS[dir] and roomExists(last.num) and getRoomArea(last.num) == areaId then
    local lx, ly, lz = getRoomCoordinates(last.num)
    local off = DIR_OFFSETS[dir]
    x, y, z = lx + off[1], ly + off[2], lz + off[3]
  else
    x, y, z = firstFreeSpot(areaId)
  end

  addRoom(info.num)
  setRoomArea(info.num, areaId)
  setRoomUserData(info.num, "gups_label", info.label)
  setRoomCoordinates(info.num, x, y, z)
end

local function syncExitsAndDoors(info)
  local exits = info.exits or {}
  local doors = info.doors or {}

  for _, d in ipairs(STANDARD_DIRS) do
    local dest = exits[d]
    if type(dest) == "number" and roomExists(dest) then
      setExit(info.num, dest, d)
      setExitStub(info.num, d, false)
    elseif dest ~= nil then
      setExit(info.num, -1, d)
      setExitStub(info.num, d, true)
    else
      setExit(info.num, -1, d)
      setExitStub(info.num, d, false)
    end
  end

  local wantedSpecial = {}
  for _, k in ipairs(sortedCustomKeys(exits)) do
    local dest = exits[k]
    if type(dest) == "number" and roomExists(dest) then
      addSpecialExit(info.num, dest, k)
      wantedSpecial[k] = true
    end
  end
  for cmd, _ in pairs(getSpecialExitsSwap(info.num)) do
    if not wantedSpecial[cmd] then
      -- Mudlet only accepts a door on a special exit that still exists.
      setDoor(info.num, cmd, 0)
      removeSpecialExit(info.num, cmd)
    end
  end

  for _, d in ipairs(COMPASS_DIRS) do
    if exits[d] ~= nil then
      setDoor(info.num, d, DOOR_STATES[doors[d]] or 0)
    else
      setDoor(info.num, d, 0)
    end
  end
  for _, k in ipairs(sortedCustomKeys(exits)) do
    if wantedSpecial[k] then
      setDoor(info.num, k, DOOR_STATES[doors[k]] or 0)
    end
  end
end

local function backLinkPreviousRoom(info)
  local last = gupsMapper.last
  if not last or last.num == info.num or not roomExists(last.num) or type(last.exits) ~= "table" then
    return
  end
  for k, dest in pairs(last.exits) do
    if dest == info.num then
      if IS_STANDARD[k] then
        setExit(last.num, info.num, k)
        setExitStub(last.num, k, false)
      else
        addSpecialExit(last.num, info.num, k)
      end
    end
  end
end

function gupsMapper.onRoomInfo()
  local info = gmcp and gmcp.Room and gmcp.Room.Info
  if not info or type(info.num) ~= "number" then
    return
  end

  local previousNum = gupsMapper.last and gupsMapper.last.num

  local areaId = ensureArea(info.area, info.area_name)
  removeStaleRooms(info.num, info.label)

  if not roomExists(info.num) then
    placeNewRoom(info, areaId)
  end

  setRoomName(info.num, info.name)
  syncExitsAndDoors(info)
  backLinkPreviousRoom(info)
  centerview(info.num)

  gupsMapper.last = deepCopy(info)

  -- A resend for the room the player is still in (a door there changed) says nothing about the
  -- step in flight.
  if gupsMapper.walk and info.num ~= previousNum then
    if info.num == gupsMapper.walk.path[gupsMapper.walk.i] then
      gupsMapper.step()
    else
      gupsMapper.walk = nil
      echo("Walk interrupted.\n")
    end
  end
end

function gupsMapper.step()
  local walk = gupsMapper.walk
  if not walk then
    return
  end
  walk.i = walk.i + 1
  if walk.i > #walk.dirs then
    gupsMapper.walk = nil
    return
  end
  gupsMapper.sending = true
  send(walk.dirs[walk.i], false)
  gupsMapper.sending = false
end

function gupsMapper.onSend()
  if gupsMapper.sending then
    return
  end
  gupsMapper.walk = nil
end

function doSpeedWalk()
  if not speedWalkDir or #speedWalkDir == 0 then
    return
  end
  gupsMapper.walk = {
    dirs = deepCopy(speedWalkDir),
    path = deepCopy(speedWalkPath or {}),
    i = 0,
  }
  gupsMapper.step()
end

if gupsMapper.handlers then
  for _, id in ipairs(gupsMapper.handlers) do
    killAnonymousEventHandler(id)
  end
end
gupsMapper.handlers = {
  registerAnonymousEventHandler("gmcp.Room.Info", gupsMapper.onRoomInfo),
  registerAnonymousEventHandler("sysDataSendRequest", gupsMapper.onSend),
}
