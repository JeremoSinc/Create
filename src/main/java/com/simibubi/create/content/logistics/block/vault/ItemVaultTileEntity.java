package com.simibubi.create.content.logistics.block.vault;

import java.util.List;
import com.simibubi.create.AllTileEntities;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.foundation.config.AllConfigs;
import com.simibubi.create.foundation.tileEntity.IMultiTileContainer;
import com.simibubi.create.foundation.tileEntity.SmartTileEntity;
import com.simibubi.create.foundation.tileEntity.TileEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;

import javax.annotation.Nullable;


public class ItemVaultTileEntity extends SmartTileEntity implements IMultiTileContainer.Inventory {
	protected LazyOptional<IItemHandler> itemCapability;

	protected ItemStackHandler inventory;
	protected BlockPos controller;
	protected BlockPos lastKnownPos;
	protected boolean updateConnectivity;
	protected int radius;
	protected int length;
	protected int color;
	protected Axis axis;

	public ItemVaultTileEntity(BlockEntityType<?> tileEntityTypeIn, BlockPos pos, BlockState state) {
		super(tileEntityTypeIn, pos, state);

		inventory = new ItemStackHandler(AllConfigs.SERVER.logistics.vaultCapacity.get()) {
			@Override
			protected void onContentsChanged(int slot) {
				super.onContentsChanged(slot);
				updateComparators();
			}
		};

		itemCapability = LazyOptional.empty();
		radius = 1;
		length = 1;
		color = 16;
	}

	@Override
	public void addBehaviours(List<TileEntityBehaviour> behaviours) {}

	protected void updateConnectivity() {
		updateConnectivity = false;
		if (level.isClientSide())
			return;
		if (!isController())
			return;
		ConnectivityHandler.formMulti(this);
	}

	@Override
	@Nullable
	public Object getExtraData() {
		return color;
	}

	@Override
	public void setExtraData(@Nullable Object data) {
		if (data instanceof Integer)
			if (data == null){
				color = 16;
			}else
				color = (Integer) data;
	}

	@Override
	public Object modifyExtraData(Object data) {
		if (data instanceof Integer colors) {
			colors |= color;
			return colors;
		}
		return data;
	}

	protected void updateComparators() {
		ItemVaultTileEntity controllerTE = getControllerTE();
		if (controllerTE == null)
			return;

		BlockPos pos = controllerTE.getBlockPos();
		for (int y = 0; y < controllerTE.radius; y++) {
			for (int z = 0; z < (controllerTE.axis == Axis.X ? controllerTE.radius : controllerTE.length); z++) {
				for (int x = 0; x < (controllerTE.axis == Axis.Z ? controllerTE.radius : controllerTE.length); x++) {
					level.updateNeighbourForOutputSignal(pos.offset(x, y, z), getBlockState().getBlock());
				}
			}
		}
	}

	public void applyColor(@Nullable DyeColor colorIn) {
		int colorID = 16;
		if (colorIn != null)
			colorID = colorIn.getId();

		System.out.println("applyColor called, setting to: "+colorID);

		for (BlockPos pos : ItemVaultBlock.getVaultBlocks(level, getController())) {
			BlockState blockState = level.getBlockState(pos);
			BlockEntity te = level.getBlockEntity(pos);
			ItemVaultTileEntity vault = (ItemVaultTileEntity) te;
			if (!ItemVaultBlock.isVault(blockState))
				continue;
			vault.getControllerTE().color = colorID;
			level.setBlock(pos, blockState.setValue(ItemVaultBlock.COLOR, colorID), 22);
			vault.setChanged();
			vault.sendData();
		}
	}

	@Override
	public void tick() {
		super.tick();

		if (lastKnownPos == null)
			lastKnownPos = getBlockPos();
		else if (!lastKnownPos.equals(worldPosition) && worldPosition != null) {
			onPositionChanged();
			return;
		}

		if (updateConnectivity)
			updateConnectivity();
	}

	@Override
	public BlockPos getLastKnownPos() {
		return lastKnownPos;
	}

	@Override
	public boolean isController() {
		return controller == null || worldPosition.getX() == controller.getX()
				&& worldPosition.getY() == controller.getY() && worldPosition.getZ() == controller.getZ();
	}

	private void onPositionChanged() {
		removeController(true);
		lastKnownPos = worldPosition;
	}

	@SuppressWarnings("unchecked")
	@Override
	public ItemVaultTileEntity getControllerTE() {
		if (isController())
			return this;
		BlockEntity tileEntity = level.getBlockEntity(controller);
		if (tileEntity instanceof ItemVaultTileEntity)
			return (ItemVaultTileEntity) tileEntity;
		return null;
	}

	public void removeController(boolean keepContents) {
		if (level.isClientSide())
			return;
		updateConnectivity = true;
		controller = null;
		radius = 1;
		length = 1;

		BlockState state = getBlockState();
		if (ItemVaultBlock.isVault(state)) {
			state = state.setValue(ItemVaultBlock.LARGE, false);
			getLevel().setBlock(worldPosition, state, 22);
		}

		itemCapability.invalidate();
		setChanged();
		sendData();
	}

	@Override
	public void setController(BlockPos controller) {
		if (level.isClientSide && !isVirtual())
			return;
		if (controller.equals(this.controller))
			return;
		this.controller = controller;
		itemCapability.invalidate();
		setChanged();
		sendData();
	}

	@Override
	public BlockPos getController() {
		return isController() ? worldPosition : controller;
	}

	@Override
	protected void read(CompoundTag compound, boolean clientPacket) {
		super.read(compound, clientPacket);

		BlockPos controllerBefore = controller;
		int prevSize = radius;
		int prevLength = length;

		updateConnectivity = compound.contains("Uninitialized");
		controller = null;
		lastKnownPos = null;

		if (compound.contains("LastKnownPos"))
			lastKnownPos = NbtUtils.readBlockPos(compound.getCompound("LastKnownPos"));
		if (compound.contains("Controller"))
			controller = NbtUtils.readBlockPos(compound.getCompound("Controller"));

		if (isController()) {
			radius = compound.getInt("Size");
			length = compound.getInt("Length");
			color = compound.getInt("Color");
		}

		if (!clientPacket) {
			inventory.deserializeNBT(compound.getCompound("Inventory"));
			return;
		}

		boolean changeOfController =
				controllerBefore == null ? controller != null : !controllerBefore.equals(controller);
		if (hasLevel() && (changeOfController || prevSize != radius || prevLength != length))
			level.setBlocksDirty(getBlockPos(), Blocks.AIR.defaultBlockState(), getBlockState());
	}

	@Override
	protected void write(CompoundTag compound, boolean clientPacket) {
		if (updateConnectivity)
			compound.putBoolean("Uninitialized", true);
		if (lastKnownPos != null)
			compound.put("LastKnownPos", NbtUtils.writeBlockPos(lastKnownPos));
		if (!isController())
			compound.put("Controller", NbtUtils.writeBlockPos(controller));
		if (isController()) {
			compound.putInt("Size", radius);
			compound.putInt("Length", length);
			compound.putInt("Color", color);
		}

		super.write(compound, clientPacket);

		if (!clientPacket) {
			compound.putString("StorageType", "CombinedInv");
			compound.put("Inventory", inventory.serializeNBT());
		}
	}

	public ItemStackHandler getInventoryOfBlock() {
		return inventory;
	}

	public void applyInventoryToBlock(ItemStackHandler handler) {
		for (int i = 0; i < inventory.getSlots(); i++)
			inventory.setStackInSlot(i, i < handler.getSlots() ? handler.getStackInSlot(i) : ItemStack.EMPTY);
	}

	@Override
	public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
		if (isItemHandlerCap(cap)) {
			initCapability();
			return itemCapability.cast();
		}
		return super.getCapability(cap, side);
	}

	private void initCapability() {
		if (itemCapability.isPresent())
			return;
		if (!isController()) {
			ItemVaultTileEntity controllerTE = getControllerTE();
			if (controllerTE == null)
				return;
			controllerTE.initCapability();
			itemCapability = controllerTE.itemCapability;
			return;
		}

		boolean alongZ = ItemVaultBlock.getVaultBlockAxis(getBlockState()) == Axis.Z;
		IItemHandlerModifiable[] invs = new IItemHandlerModifiable[length * radius * radius];
		for (int yOffset = 0; yOffset < length; yOffset++) {
			for (int xOffset = 0; xOffset < radius; xOffset++) {
				for (int zOffset = 0; zOffset < radius; zOffset++) {
					BlockPos vaultPos = alongZ ? worldPosition.offset(xOffset, zOffset, yOffset)
							: worldPosition.offset(yOffset, xOffset, zOffset);
					ItemVaultTileEntity vaultAt =
							ConnectivityHandler.partAt(AllTileEntities.ITEM_VAULT.get(), level, vaultPos);
					invs[yOffset * radius * radius + xOffset * radius + zOffset] =
							vaultAt != null ? vaultAt.inventory : new ItemStackHandler();
				}
			}
		}

		CombinedInvWrapper combinedInvWrapper = new CombinedInvWrapper(invs);
		itemCapability = LazyOptional.of(() -> combinedInvWrapper);
	}

	public static int getMaxLength(int radius) {
		return radius * 3;
	}

	@Override
	public void preventConnectivityUpdate() { updateConnectivity = false; }

	@Override
	public void notifyMultiUpdated() {
		BlockState state = this.getBlockState();
		if (ItemVaultBlock.isVault(state)) { // safety
			level.setBlock(getBlockPos(), state.setValue(ItemVaultBlock.LARGE, radius > 2), 6);
		}
		if (isController()){
			if (color < 16) {
				applyColor(DyeColor.byId(color));
			}else
				applyColor(null);
		}
		itemCapability.invalidate();
		setChanged();
	}

	@Override
	public Direction.Axis getMainConnectionAxis() { return getMainAxisOf(this); }

	@Override
	public int getMaxLength(Direction.Axis longAxis, int width) {
		if (longAxis == Direction.Axis.Y) return getMaxWidth();
		return getMaxLength(width);
	}

	@Override
	public int getMaxWidth() {
		return 3;
	}

	@Override
	public int getHeight() { return length; }

	@Override
	public int getWidth() { return radius; }

	public int getColor() { return color; }

	public void setColor(int color) {this.color = color;}

	@Override
	public void setHeight(int height) { this.length = height; }

	@Override
	public void setWidth(int width) { this.radius = width; }

	@Override
	public boolean hasInventory() { return true; }
}
