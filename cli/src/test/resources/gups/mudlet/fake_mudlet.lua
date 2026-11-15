fake = {
  rooms = {},
  areas = {},
  nextAreaId = 1,
  handlers = {},
  nextHandlerId = 1,
  centered = nil,
  sent = {},
  echoed = {},
  doorCalls = {},
}

gmcp = gmcp or {}
gmcp.Room = gmcp.Room or {}

function registerAnonymousEventHandler(event, fn)
  local id = fake.nextHandlerId
  fake.nextHandlerId = id + 1
  fake.handlers[id] = { event = event, fn = fn }
  return id
end

function killAnonymousEventHandler(id)
  fake.handlers[id] = nil
end

function raiseEvent(event, ...)
  local ids = {}
  for id, entry in pairs(fake.handlers) do
    if entry.event == event then
      ids[#ids + 1] = id
    end
  end
  table.sort(ids)
  for _, id in ipairs(ids) do
    local entry = fake.handlers[id]
    if entry then
      entry.fn(event, ...)
    end
  end
end

function roomExists(id)
  return fake.rooms[id] ~= nil
end

function addRoom(id)
  fake.rooms[id] = {
    name = "",
    area = -1,
    x = 0,
    y = 0,
    z = 0,
    exits = {},
    stubs = {},
    special = {},
    doors = {},
    data = {},
  }
  return true
end

function deleteRoom(id)
  fake.rooms[id] = nil
  for _, room in pairs(fake.rooms) do
    for dir, dest in pairs(room.exits) do
      if dest == id then
        room.exits[dir] = nil
      end
    end
    for cmd, dest in pairs(room.special) do
      if dest == id then
        room.special[cmd] = nil
      end
    end
  end
end

function setRoomName(id, name)
  fake.rooms[id].name = name
end

function setRoomArea(id, areaId)
  fake.rooms[id].area = areaId
end

function getRoomArea(id)
  return fake.rooms[id].area
end

function setRoomCoordinates(id, x, y, z)
  local r = fake.rooms[id]
  r.x = x
  r.y = y
  r.z = z
end

function getRoomCoordinates(id)
  local r = fake.rooms[id]
  return r.x, r.y, r.z
end

function getRoomsByPosition(areaId, x, y, z)
  local res = {}
  local idx = 0
  local ids = {}
  for id in pairs(fake.rooms) do
    ids[#ids + 1] = id
  end
  table.sort(ids)
  for _, id in ipairs(ids) do
    local r = fake.rooms[id]
    if r.area == areaId and r.x == x and r.y == y and r.z == z then
      res[idx] = id
      idx = idx + 1
    end
  end
  return res
end

function setExit(from, to, dir)
  local r = fake.rooms[from]
  if to == -1 then
    r.exits[dir] = nil
  else
    r.exits[dir] = to
  end
end

function setExitStub(id, dir, on)
  local r = fake.rooms[id]
  if on then
    r.stubs[dir] = true
  else
    r.stubs[dir] = nil
  end
end

function addSpecialExit(from, to, cmd)
  fake.rooms[from].special[cmd] = to
end

function removeSpecialExit(id, cmd)
  fake.rooms[id].special[cmd] = nil
end

function getSpecialExitsSwap(id)
  local copy = {}
  for cmd, dest in pairs(fake.rooms[id].special) do
    copy[cmd] = dest
  end
  return copy
end

function setDoor(id, cmd, status)
  fake.doorCalls[#fake.doorCalls + 1] = { id = id, dir = cmd, status = status }
  local r = fake.rooms[id]
  if status == 0 then
    r.doors[cmd] = nil
  else
    r.doors[cmd] = status
  end
end

function setRoomUserData(id, key, value)
  fake.rooms[id].data[key] = tostring(value)
end

function getRoomUserData(id, key)
  return fake.rooms[id].data[key] or ""
end

function searchRoomUserData(key, value)
  local res = {}
  for id, r in pairs(fake.rooms) do
    if r.data[key] == value then
      res[#res + 1] = id
    end
  end
  table.sort(res)
  return res
end

function addAreaName(name)
  for _, area in pairs(fake.areas) do
    if area.name == name then
      return nil
    end
  end
  local id = fake.nextAreaId
  fake.nextAreaId = id + 1
  fake.areas[id] = { name = name, data = {} }
  return id
end

function getAreaTableSwap()
  local res = {}
  for id, area in pairs(fake.areas) do
    res[id] = area.name
  end
  return res
end

function setAreaName(id, name)
  fake.areas[id].name = name
end

function setAreaUserData(id, key, value)
  fake.areas[id].data[key] = tostring(value)
end

function getAreaUserData(id, key)
  return fake.areas[id].data[key] or ""
end

function centerview(id)
  fake.centered = id
end

function send(cmd, echoFlag)
  fake.sent[#fake.sent + 1] = cmd
  raiseEvent("sysDataSendRequest", cmd)
end

function echo(text)
  fake.echoed[#fake.echoed + 1] = text
end
