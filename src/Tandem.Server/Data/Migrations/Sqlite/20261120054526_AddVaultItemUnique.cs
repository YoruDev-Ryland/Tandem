using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tandem.Server.Data.Migrations.Sqlite
{
    /// <inheritdoc />
    public partial class AddVaultItemUnique : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            // Items without an emulator now store "" (the unique key cannot rely on NULLs).
            migrationBuilder.Sql("UPDATE \"VaultItems\" SET \"EmulatorId\" = '' WHERE \"EmulatorId\" IS NULL;");

            migrationBuilder.DropIndex(
                name: "IX_VaultItems_UserId_Category",
                table: "VaultItems");

            migrationBuilder.CreateIndex(
                name: "IX_VaultItems_UserId_Category_EmulatorId_Name",
                table: "VaultItems",
                columns: new[] { "UserId", "Category", "EmulatorId", "Name" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_VaultItems_UserId_Category_EmulatorId_Name",
                table: "VaultItems");

            migrationBuilder.CreateIndex(
                name: "IX_VaultItems_UserId_Category",
                table: "VaultItems",
                columns: new[] { "UserId", "Category" });
        }
    }
}
