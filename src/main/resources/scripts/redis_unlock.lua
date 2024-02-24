-- 获取变量
local key = KEYS[1]
local val = ARGV[1]

-- 判断
local res = redis.call('get', key)
if(res == val) then
	return redis.call('del', key)
end
return 0